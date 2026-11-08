package com.mystock.jobs;

import com.mystock.db.Database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * Loads the CSV files written by the unchanged legacy code (out.csv, port.csv, pred.csv, train.csv)
 * into DuckDB. Values keep the legacy "#.##" formatting/rounding because the legacy writer produced them.
 */
public final class CsvLoader {
    private CsvLoader() {}

    public static final String[] STOCK_COLS = ("ticker,name,sect,ind,mktsize,symid,mktcap,pe,eps,dividend,shares,ttmearnings," +
            "ttmbookvalue,ttmrevenue,ttmgrossprofit,daypnl,weekpnl,mompnl,qoqpnl,yoypnl,pnldir,cagr,corr,upcorr,downcorr," +
            "coskew,range,rangescore,lastdevz,seasonality,seasonindex,xadv,avgdr,momtr,px2bbpct,volratio,signal,aroonup," +
            "aroondwn,hangtime,peak30,drop30,qpeak,prscore,qdrop,qrebound,precovpx,decovpx,kurt,trisktouch,triskbreach," +
            "triskvol,qdropavg,volratioyy,maxpctchg,sharpness,volscore,histvol,optvol,optivol,rangevol,rsi,weekdip,momdip," +
            "bumpiness,r2,maxdrs,mindrs,stlpm,dtpm,ema20,ema60,ema200,beta,alpha,lastpx,mktrscore,mktdip,mktsindex,summ," +
            "impvol,earnings,income,revenue").split(",");
    static final Set<String> STOCK_TEXT = Set.of("ticker", "name", "sect", "ind", "mktsize", "summ", "income", "revenue");

    public static final String[] PORT_COLS = ("name,daypnl,weekpnl,mompnl,qoqpnl,yoypnl,roi,corr,upcorr,downcorr,coskew," +
            "seasonality,seasonindex,aroonup,aroondwn,hangtime,peak30,drop30,px2bbpct,qpeak,momtr,rangescore,prscore," +
            "qdrop,qrebound,range,lastdevz,optvol,optivol,kurt,trisktouch,triskbreach,rsi,weekdip,momdip,bumpiness,r2," +
            "histvol,stlpm,dtpm,ema20,ema60,ema200,beta,alpha,mktrscore,mktdip,mktsindex,wgtype").split(",");

    /** DecimalFormat renders infinities as the ∞ sign; NaN stays "NaN". */
    static String num(String col) {
        return "TRY_CAST(replace(\"" + col + "\", '∞', 'inf') AS DOUBLE)";
    }

    private static String readCsv(Path file) {
        return "read_csv('" + file.toAbsolutePath().toString().replace("'", "''") +
                "', header=true, all_varchar=true, delim=',', quote='\"', strict_mode=false)";
    }

    /** out.csv -> stock_metrics (rows for this asof are replaced). Returns row count. */
    public static int loadStockMetrics(Path file, java.time.LocalDate asof) throws Exception {
        if (!Files.exists(file)) throw new IllegalStateException(file + " not produced");
        StringBuilder sel = new StringBuilder("SELECT ?::DATE");
        for (String c : STOCK_COLS) {
            sel.append(", ");
            if (STOCK_TEXT.contains(c)) sel.append('"').append(c).append('"');
            else if (c.equals("symid") || c.equals("earnings")) sel.append("TRY_CAST(TRY_CAST(\"").append(c).append("\" AS DOUBLE) AS INTEGER)");
            else sel.append(num(c));
        }
        sel.append(" FROM ").append(readCsv(file));
        return replace("stock_metrics", "\"asof\", " + String.join(", ", quote(STOCK_COLS)), sel.toString(), asof);
    }

    /** port.csv -> portfolio_metrics. */
    public static int loadPortfolioMetrics(Path file, java.time.LocalDate asof) throws Exception {
        if (!Files.exists(file)) throw new IllegalStateException(file + " not produced");
        StringBuilder sel = new StringBuilder("SELECT ?::DATE");
        for (String c : PORT_COLS) {
            sel.append(", ");
            if (c.equals("name") || c.equals("wgtype")) sel.append('"').append(c).append('"');
            else sel.append(num(c));
        }
        sel.append(" FROM ").append(readCsv(file));
        return replace("portfolio_metrics", "\"asof\", " + String.join(", ", quote(PORT_COLS)), sel.toString(), asof);
    }

    private static List<String> quote(String[] cols) {
        List<String> q = new ArrayList<>();
        for (String c : cols) q.add('"' + c + '"');
        return q;
    }

    private static int replace(String table, String cols, String select, java.time.LocalDate asof) throws Exception {
        try (Connection c = Database.current().connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement("DELETE FROM " + table + " WHERE \"asof\" = ?")) {
                del.setDate(1, java.sql.Date.valueOf(asof));
                del.executeUpdate();
            }
            int n;
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO " + table + " (" + cols + ") " + select)) {
                ins.setDate(1, java.sql.Date.valueOf(asof));
                n = ins.executeUpdate();
            }
            c.commit();
            return n;
        }
    }

    /** StockFeat.builder series -> portfolio_series (replaced wholesale). */
    public static void loadPortfolioSeries(Map<String, Map<String, TreeMap<Integer, Double>>> series) throws Exception {
        try (Connection c = Database.current().connect()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) { st.execute("DELETE FROM portfolio_series"); }
            try (org.duckdb.DuckDBAppender ap = ((org.duckdb.DuckDBConnection) c).createAppender(org.duckdb.DuckDBConnection.DEFAULT_SCHEMA, "portfolio_series")) {
                for (var e : series.entrySet())
                    for (var k : e.getValue().entrySet())
                        for (var p : k.getValue().entrySet()) {
                            ap.beginRow();
                            ap.append(e.getKey());
                            ap.append(k.getKey());
                            ap.append(com.mystock.api.Arrow.ymd(p.getKey()));
                            ap.append(p.getValue());
                            ap.endRow();
                        }
            }
            c.commit();
        }
    }

    /** pred.csv -> model_features (replaced), train.csv -> model_training (replaced). */
    public static int loadModel(Path pred, Path train, java.time.LocalDate asof) throws Exception {
        int n = 0;
        try (Connection c = Database.current().connect(); Statement st = c.createStatement()) {
            if (Files.exists(pred)) {
                st.execute("CREATE OR REPLACE TABLE model_features AS SELECT DATE '" + asof + "' AS \"asof\", * FROM read_csv('"
                        + pred.toAbsolutePath().toString().replace("'", "''") + "', header=true)");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM model_features")) { rs.next(); n += rs.getInt(1); }
            }
            if (train != null && Files.exists(train)) {
                st.execute("CREATE OR REPLACE TABLE model_training AS SELECT * REPLACE (strptime(CAST(trddt AS VARCHAR), '%Y%m%d')::DATE AS trddt) FROM read_csv('"
                        + train.toAbsolutePath().toString().replace("'", "''") + "', header=true)");
            }
        }
        return n;
    }
}
