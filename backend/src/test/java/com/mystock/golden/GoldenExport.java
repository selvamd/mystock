package com.mystock.golden;

import com.mystock.App;
import com.mystock.config.Config;
import com.mystock.db.Database;
import com.mystock.tools.SeedDemo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Map;

/**
 * Test utility (not a test): seeds the deterministic demo dataset for a fixed end date into a temp DuckDB and
 * exports it as CSV in the LEGACY SQLite layout (symid-keyed int-cents quotes, yyyymmdd ints), so the
 * original legacy classes can be run on exactly the same inputs to produce golden outputs.
 * Usage: java -cp ... com.mystock.golden.GoldenExport OUT_DIR
 */
public final class GoldenExport {
    public static final LocalDate END = LocalDate.of(2025, 6, 30);

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        Path data = Files.createTempDirectory("golden-db");
        App.init(new Config(Map.of("MYSTOCK_DATA_DIR", data.toString(), "MYSTOCK_TZ", "America/New_York", "MYSTOCK_YAHOO_ENABLED", "false")));
        try (Database db = Database.open(data.resolve("mystock.duckdb"))) {
            new SeedDemo().run(END, false);
            try (Connection c = db.connect(); Statement st = c.createStatement()) {
                copy(st, "SELECT * FROM symbols ORDER BY symid", out.resolve("stocks.csv"));
                copy(st, "SELECT * FROM price_status ORDER BY symid", out.resolve("stocks_status.csv"));
                copy(st, "SELECT s.symid AS ticker, CAST(strftime(p.\"asof\", '%Y%m%d') AS INTEGER) AS \"asof\", CAST(round(open*100) AS INTEGER) AS open, " +
                        "CAST(round(high*100) AS INTEGER) AS high, CAST(round(low*100) AS INTEGER) AS low, CAST(round(close*100) AS INTEGER) AS close, " +
                        "CAST(round(adjclose*100) AS INTEGER) AS adjclose, volume AS adv FROM prices p JOIN symbols s ON s.ticker = p.ticker ORDER BY 1, 2",
                        out.resolve("quotes.csv"));
                copy(st, "SELECT * FROM stock_vol ORDER BY ticker", out.resolve("stockvol.csv"));
                copy(st, "SELECT ticker, CAST(row_number() OVER (PARTITION BY ticker ORDER BY fiscal_date DESC) - 1 AS INTEGER) AS pkey, " +
                        "max(value) FILTER (WHERE field = 'netIncome') AS net_income, max(value) FILTER (WHERE field = 'totalRevenue') AS total_revenue " +
                        "FROM financials WHERE statement = 'income' AND period = 'annual' GROUP BY ticker, fiscal_date ORDER BY ticker, pkey",
                        out.resolve("pnlstmt.csv"));
            }
        }
        System.out.println("exported legacy-layout CSVs to " + out);
    }

    private static void copy(Statement st, String sql, Path file) throws Exception {
        st.execute("COPY (" + sql + ") TO '" + file.toAbsolutePath() + "' (HEADER, DELIMITER ',')");
    }
}
