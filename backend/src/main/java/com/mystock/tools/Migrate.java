package com.mystock.tools;

import com.mystock.db.Database;
import com.mystock.services.AuthService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports a legacy /data directory (SQLite files + CSVs) using DuckDB's sqlite scanner.
 * Missing files are skipped. Prices: int cents -> dollars (cents/100.0, so the legacy cents round-trip
 * exactly through the PriceDB adapter); yyyymmdd ints -> DATE; plaintext passwords -> PBKDF2.
 */
public final class Migrate {

    public void run(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException(dir + " is not a directory");
        try (Connection c = Database.current().connect(); Statement st = c.createStatement()) {
            st.execute("INSTALL sqlite");
            st.execute("LOAD sqlite");
            // Legacy SQLite columns are loosely typed (e.g. "long", text holding numbers): read everything as
            // VARCHAR and cast explicitly.
            st.execute("SET GLOBAL sqlite_all_varchar = true");
            step(st, dir, "usstocks.db", "us", List.of(
                    "INSERT OR REPLACE INTO symbols SELECT * REPLACE (CAST(symid AS INTEGER) AS symid, TRY_CAST(etlstatus AS INTEGER) AS etlstatus) FROM us.stocks",
                    "INSERT OR REPLACE INTO price_status SELECT CAST(symid AS INTEGER), TRY_CAST(lastdt AS INTEGER), TRY_CAST(lastpx AS INTEGER) FROM us.stocks_status"));
            step(st, dir, "eodmktpx.db", "px", List.of(
                    "INSERT OR REPLACE INTO prices SELECT s.ticker, strptime(CAST(q.\"asof\" AS VARCHAR), '%Y%m%d')::DATE, " +
                            "CAST(q.open AS BIGINT)/100.0, CAST(q.high AS BIGINT)/100.0, CAST(q.low AS BIGINT)/100.0, CAST(q.close AS BIGINT)/100.0, " +
                            "CAST(q.adjclose AS BIGINT)/100.0, CAST(q.adv AS BIGINT) FROM px.quotes q JOIN symbols s ON s.symid = CAST(q.ticker AS INTEGER)"));
            step(st, dir, "stockvol.db", "sv", List.of(
                    "INSERT OR REPLACE INTO stock_vol SELECT ticker, TRY_CAST(next_earnings_date AS DOUBLE), TRY_CAST(pe_ratio AS DOUBLE), TRY_CAST(mktcap AS DOUBLE), TRY_CAST(impliedvol AS DOUBLE), TRY_CAST(TRY_CAST(lastupdate AS DOUBLE) AS BIGINT) FROM sv.stockvol"));
            step(st, dir, "oxeodmktpx.db", "ox", List.of(
                    "INSERT OR REPLACE INTO option_quotes SELECT contractID, symbol, type, strptime(CAST(\"asof\" AS VARCHAR), '%Y%m%d')::DATE, " +
                            "strptime(CAST(expiration AS VARCHAR), '%Y%m%d')::DATE, strike::DOUBLE/100.0, last::DOUBLE/100.0, mark::DOUBLE/100.0, bid::DOUBLE/100.0, bid_size::INTEGER, " +
                            "ask::DOUBLE/100.0, ask_size::INTEGER, volume::BIGINT, open_interest::BIGINT, implied_volatility::DOUBLE/10000.0, delta::DOUBLE/10000.0, gamma::DOUBLE/10000.0, " +
                            "theta::DOUBLE/10000.0, vega::DOUBLE/10000.0, rho::DOUBLE/10000.0, upx::DOUBLE/100.0 FROM ox.quotes",
                    "INSERT OR REPLACE INTO stock_features SELECT * REPLACE (strptime(CAST(\"asof\" AS VARCHAR), '%Y%m%d')::DATE AS \"asof\") FROM ox.stockfeatures"));
            financials(st, dir);
            trades(c, st, dir);
            csv(st, dir.resolve("queries.csv"), "INSERT OR REPLACE INTO saved_queries SELECT name, value FROM read_csv(?, header=true, all_varchar=true)");
            csv(st, dir.resolve("dynamic_reports.csv"), "INSERT INTO dynamic_reports SELECT \"function\", report, params FROM read_csv(?, header=true, all_varchar=true)");
            csv(st, dir.resolve("prediction.csv"), "INSERT INTO predictions SELECT ticker, " +
                    "CASE WHEN length(CAST(\"asof\" AS VARCHAR)) = 8 THEN strptime(CAST(\"asof\" AS VARCHAR), '%Y%m%d')::DATE ELSE TRY_CAST(\"asof\" AS DATE) END, " +
                    "TRY_CAST(tgtpx AS DOUBLE), TRY_CAST(open AS DOUBLE), TRY_CAST(high AS DOUBLE), TRY_CAST(low AS DOUBLE), TRY_CAST(close AS DOUBLE), " +
                    "COLUMNS('^reason$') FROM read_csv(?, header=true, all_varchar=true)");
        }
        System.out.println("migration finished; run `task STOCKINFO` and `task STOCKFEAT` (or start the server and run EOD) to compute snapshots");
    }

    private static boolean attach(Statement st, Path file, String alias) throws SQLException {
        if (!Files.exists(file)) { System.out.println("skip " + file.getFileName() + " (not found)"); return false; }
        st.execute("ATTACH '" + file.toAbsolutePath().toString().replace("'", "''") + "' AS " + alias + " (TYPE sqlite, READ_ONLY)");
        return true;
    }

    private static void step(Statement st, Path dir, String file, String alias, List<String> sqls) throws SQLException {
        if (!attach(st, dir.resolve(file), alias)) return;
        try {
            for (String sql : sqls) {
                try (Statement s2 = st.getConnection().createStatement()) {
                    int n = s2.executeUpdate(sql);
                    System.out.println(file + ": " + n + " rows <- " + sql.substring(0, Math.min(60, sql.length())) + "...");
                } catch (SQLException e) {
                    System.out.println(file + ": step skipped (" + e.getMessage().split("\n")[0] + ")");
                }
            }
        } finally {
            st.execute("DETACH " + alias);
        }
    }

    /** financials.db:pnlstmt (wide, one row per ticker and pkey; pkey 0 = most recent) -> long format. */
    private static void financials(Statement st, Path dir) throws SQLException {
        if (!attach(st, dir.resolve("financials.db"), "fin")) return;
        try {
            List<String> cols = new ArrayList<>();
            try (Statement s2 = st.getConnection().createStatement(); ResultSet rs = s2.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_catalog = 'fin' AND table_name = 'pnlstmt'")) {
                while (rs.next()) {
                    String c = rs.getString(1);
                    if (!c.equalsIgnoreCase("ticker") && !c.equalsIgnoreCase("pkey") && !c.equalsIgnoreCase("asofdt")) cols.add(c);
                }
            }
            for (String col : cols) {
                String field = com.mystock.legacy.LegacyNames.camel(col);
                String sql = "INSERT OR REPLACE INTO financials SELECT ticker, 'income', make_date(year(current_date) - 1 - TRY_CAST(pkey AS INTEGER), 12, 31), " +
                        "'annual', '" + field + "', TRY_CAST(\"" + col + "\" AS DOUBLE), TIMESTAMP '2000-01-01' FROM fin.pnlstmt " +
                        "WHERE TRY_CAST(pkey AS INTEGER) IS NOT NULL AND TRY_CAST(\"" + col + "\" AS DOUBLE) IS NOT NULL";
                try (Statement s2 = st.getConnection().createStatement()) { s2.executeUpdate(sql); } catch (SQLException e) { System.out.println("financials." + col + " skipped: " + e.getMessage().split("\n")[0]); }
            }
            System.out.println("financials.db: " + cols.size() + " fields imported (marked stale so they refresh from Alpha Vantage)");
        } finally {
            st.execute("DETACH fin");
        }
    }

    private static void trades(Connection c, Statement st, Path dir) throws SQLException {
        if (!attach(st, dir.resolve("trades.db"), "tr")) return;
        try {
            try (Statement s2 = c.createStatement()) {
                int n = s2.executeUpdate("INSERT OR REPLACE INTO trades SELECT trades_id, login, portfolio, position, buysell, legsym, px, qty, " +
                        "strptime(CAST(CASE WHEN tradedt::INTEGER < 1000000 THEN tradedt::INTEGER + 20000000 ELSE tradedt::INTEGER END AS VARCHAR), '%Y%m%d')::DATE, upx::DOUBLE, uvol::DOUBLE, current_timestamp FROM tr.trades_log");
                System.out.println("trades.db: " + n + " trades");
            } catch (SQLException e) { System.out.println("trades_log skipped: " + e.getMessage().split("\n")[0]); }
            List<String[]> users = new ArrayList<>();
            try (Statement s2 = c.createStatement(); ResultSet rs = s2.executeQuery("SELECT login, pass FROM tr.login")) {
                while (rs.next()) users.add(new String[]{rs.getString(1), rs.getString(2)});
            } catch (SQLException e) { System.out.println("login skipped: " + e.getMessage().split("\n")[0]); }
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO users (login, pass_hash) VALUES (?, ?)")) {
                for (String[] u : users) {
                    if (u[0] == null || u[1] == null) continue;
                    ps.setString(1, u[0]);
                    ps.setString(2, AuthService.hash(u[1]));
                    ps.executeUpdate();
                }
            }
            System.out.println("trades.db: " + users.size() + " users (passwords hashed)");
        } finally {
            st.execute("DETACH tr");
        }
    }

    private static void csv(Statement st, Path file, String sql) {
        if (!Files.exists(file)) { System.out.println("skip " + file.getFileName() + " (not found)"); return; }
        try (PreparedStatement ps = st.getConnection().prepareStatement(sql)) {
            ps.setString(1, file.toAbsolutePath().toString());
            System.out.println(file.getFileName() + ": " + ps.executeUpdate() + " rows");
        } catch (SQLException e) {
            System.out.println(file.getFileName() + " skipped: " + e.getMessage().split("\n")[0]);
        }
    }
}
