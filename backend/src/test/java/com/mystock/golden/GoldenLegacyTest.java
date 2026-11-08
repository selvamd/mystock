package com.mystock.golden;

import com.mystock.Fixture;
import com.mystock.config.Config;
import com.mystock.db.Database;
import com.mystock.jobs.JobRunner;
import com.mystock.legacy.Legacy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end golden test. The goldens in src/test/resources/golden were produced by compiling the ORIGINAL
 * legacy sources (mystk/java, SQLite) and running them on the same deterministic dataset, exported in the
 * legacy SQLite layout by {@link GoldenExport}. Here the copied classes run on DuckDB through the adapters;
 * outputs must be identical.
 */
class GoldenLegacyTest {

    @BeforeAll
    static void setup() throws Exception {
        Fixture.seeded(); // seed-demo with END = 2025-06-30, runs STOCKINFO + STOCKFEAT
    }

    static List<String> resource(String name) throws Exception {
        try (InputStream in = GoldenLegacyTest.class.getResourceAsStream("/golden/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    @Test
    void stockInfoOutCsvIdenticalToLegacy() throws Exception {
        List<String> gold = resource("out.csv");
        List<String> mine = Files.readAllLines(Config.get().workDir().resolve("out.csv"));
        assertEquals(gold.get(0), mine.get(0), "header");
        int earnings = Arrays.asList(gold.get(0).split(",")).indexOf("earnings");
        assertEquals(gold.size(), mine.size(), "row count");
        for (int i = 1; i < gold.size(); i++) {
            String[] g = gold.get(i).split(",", -1), m = mine.get(i).split(",", -1);
            assertEquals(g.length, m.length, "width row " + i);
            for (int c = 0; c < g.length; c++) {
                if (c == earnings) continue; // days-to-earnings depends on the wall clock at generation time
                assertEquals(g[c], m[c], "row " + i + " (" + g[0] + ") column " + gold.get(0).split(",")[c]);
            }
        }
        try (Connection c = Database.current().connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*), count(DISTINCT \"asof\") FROM stock_metrics")) {
            rs.next();
            assertEquals(gold.size() - 1, rs.getInt(1));
            assertEquals(1, rs.getInt(2));
        }
    }

    @Test
    void stockFeatPortCsvIdenticalToLegacy() throws Exception {
        List<String> gold = resource("port.csv");
        List<String> mine = Files.readAllLines(Config.get().workDir().resolve("port.csv"));
        assertEquals(gold, mine);
        try (Connection c = Database.current().connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM portfolio_metrics")) {
            rs.next();
            assertEquals(gold.size() - 1, rs.getInt(1));
        }
    }

    static void dump(List<String> out, String kind, String t, String m, Map<Integer, Double> res) {
        double sum = 0;
        for (double d : res.values()) sum += d;
        out.add(kind + "," + t + "," + m + ",count," + res.size() + "," + Double.toString(sum));
        List<Integer> keys = new ArrayList<>(res.keySet());
        for (int i = Math.max(0, keys.size() - 40); i < keys.size(); i++)
            out.add(kind + "," + t + "," + m + "," + keys.get(i) + "," + Double.toString(res.get(keys.get(i))));
    }

    @Test
    void metricOhlcPriceIdenticalToLegacy() throws Exception {
        Legacy.reload();
        List<String> mine = new ArrayList<>();
        String[] ms = ("OPEN,HIGH,LOW,PREV,MKTPX,RSI,STDMKTPX,ROI,DAYPNL,WEEKPNL,MOMPNL,QOQPNL,YOYPNL,DTPM,STLPM,OPTVOL,OPTIVOL,EMA20," +
                "EMA60,EMA200,RANGESCORE,LASTDEVZ,CORR,BUMPINESS,PRSCORE,KURT,CCI,CCIQ,COSKEW,PCTCHG,ATR,ADV,TRMON,PNLDIR,MOMDIP").split(",");
        for (String t : new String[]{"SPY", "AAPL"})
            for (String m : ms) dump(mine, "METRIC", t, m, Legacy.metric(t, m));
        for (String f : "OPEN,HIGH,LOW,CLOSE,PREV,ADV".split(","))
            dump(mine, "OHLC", "AAPL", f, Legacy.ohlcRange(f, 20150101, 20990101, "AAPL"));
        for (String f : "OPEN,CLOSE,PREV,ADV".split(",")) {
            List<Double> v = Legacy.ohlcAsof(f, 20240601, List.of("MSFT", "AAPL", "NOPE"));
            Map<Integer, Double> m = new LinkedHashMap<>();
            for (int i = 0; i < v.size(); i++) m.put(i + 1, v.get(i));
            dump(mine, "OHLCASOF", "MSFT|AAPL|NOPE", f, m);
        }
        String[][] ser = {{"AAPL", "STK"}, {"TECH", "EWT"}, {"MKT", "MWT"}, {"MKT", "AWT"}, {"FANG", "EWT"}};
        for (String[] s : ser) dump(mine, "PRICE", s[0], s[1], Legacy.series(s[0], s[1]));
        List<String> gold = resource("metrics.csv");
        assertEquals(gold.size(), mine.size());
        for (int i = 0; i < gold.size(); i++) assertEquals(gold.get(i), mine.get(i), "line " + (i + 1));
    }

    @Test
    void shadowBacktestIdenticalToLegacy() throws Exception {
        Legacy.reload();
        assertEquals(resource("shadow.csv"), Legacy.shadow("SPY", 20250501, null, null));
        assertEquals(resource("shadow_vols.csv"), Legacy.shadow("AAPL", 20250401, "0.25", "0.35"));
    }

    @Test
    void optFeatStockFeaturesMatchLegacy() throws Exception {
        assertTrue(new JobRunner().runNow("OPTFEAT"));
        List<String> gold = resource("stockfeatures.csv"); // sqlite3 -csv output (15 significant digits)
        String[] head = gold.get(0).split(",");
        Map<String, String[]> byTicker = new HashMap<>();
        for (int i = 1; i < gold.size(); i++) byTicker.put(gold.get(i).split(",")[0], gold.get(i).split(",", -1));
        int rows = 0;
        try (Connection c = Database.current().connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM stock_features ORDER BY symbol")) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                rows++;
                String[] g = byTicker.get(rs.getString("symbol"));
                assertNotNull(g, "unexpected symbol " + rs.getString("symbol"));
                for (int col = 2; col < head.length; col++) {
                    double exp = g[col].isEmpty() ? Double.NaN : Double.parseDouble(g[col]);
                    double act = rs.getDouble(head[col]);
                    if (rs.wasNull()) act = Double.NaN;
                    String msg = rs.getString("symbol") + "." + head[col];
                    if (Double.isNaN(exp)) { assertTrue(Double.isNaN(act), msg); continue; }
                    assertEquals(exp, act, Math.max(1e-9, Math.abs(exp) * 1e-12), msg);
                }
            }
        }
        assertEquals(byTicker.size(), rows);
    }

    @Test
    void legacyModelOverflowFixed() throws Exception {
        // Legacy StockModel.pred recursed forever through StockFeature.ref == this (golden/model_legacy.txt).
        assertEquals(List.of("java.lang.StackOverflowError"), resource("model_legacy.txt"));
        assertTrue(new JobRunner().runNow("MODEL"));
        try (Connection c = Database.current().connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM model_features")) {
            rs.next();
            assertTrue(rs.getInt(1) > 20);
        }
    }
}
