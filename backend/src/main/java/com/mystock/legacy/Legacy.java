package com.mystock.legacy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ADDED (boundary): the only entry point from new code into the legacy analytics.
 * <p>
 * The legacy math keeps its static mutable globals (Context.*, Dates.dates, StockFeat.map/builder,
 * StockReportSvc.*, TimeSeries windows). Every call that reads or writes them goes through {@link #LOCK}
 * (one fair lock = a single-threaded analytics section). Socket I/O, auth, trades and plain SQL stay
 * concurrent and never take this lock.
 */
public final class Legacy {
    public static final ReentrantLock LOCK = new ReentrantLock(true);
    private static volatile boolean ready = false;
    private static volatile int dbdate = 0;

    private Legacy() {}

    public static <T> T locked(Callable<T> c) throws Exception {
        LOCK.lockInterruptibly();
        try {
            return c.call();
        } finally {
            LOCK.unlock();
        }
    }

    public static void lockedRun(ThrowingRunnable r) throws Exception {
        locked(() -> { r.run(); return null; });
    }

    public interface ThrowingRunnable { void run() throws Exception; }

    /** Points legacy CSV outputs (out.csv, port.csv, pred.csv, train.csv, symbols.txt) at a work dir. */
    public static void setWorkDir(Path dir) throws Exception {
        Files.createDirectories(dir);
        Const.path = dir.toAbsolutePath() + "/";
    }

    public static String workPath(String file) { return Const.path + file; }

    /** Legacy INIT: StockReportSvc.init() (Context.init + StockFeat.init + loadPortfolio). */
    public static boolean reload() throws Exception {
        return locked(() -> {
            StockReportSvc.dbdate = 0;
            ready = false;
            StockReportSvc.init(); // legacy catches and prints its own exceptions
            boolean ok = StockReportSvc.dbdate > 0 && Dates.dates != null && Context.spydr != null
                    && StockReportSvc.sym2idx != null;
            ready = ok;
            dbdate = ok ? StockReportSvc.dbdate : 0;
            return ok;
        });
    }

    public static boolean ready() { return ready; }

    /** Last trading date of the market reference (RSP) as yyyymmdd, 0 when no data is loaded. */
    public static int dbdate() { return dbdate; }

    private static void requireReady() {
        if (!ready) throw new NoMarketData();
    }

    public static final class NoMarketData extends RuntimeException {
        public NoMarketData() { super("no market data loaded (run seed-demo or the EOD job)"); }
    }

    public static final class NotInUniverse extends RuntimeException {
        public NotInUniverse(String t) { super("ticker " + t + " is not in the analytics universe (mktcap > 1e9 or listed ETF)"); }
    }

    public static boolean inUniverse(String ticker) throws Exception {
        return locked(() -> ready && StockReportSvc.sym2idx.containsKey(ticker));
    }

    // ------------------------------------------------------------------ METRIC
    public static TreeMap<Integer, Double> metric(String ticker, String metric) throws Exception {
        return locked(() -> {
            requireReady();
            if (!StockReportSvc.sym2idx.containsKey(ticker)) throw new NotInUniverse(ticker);
            StockReportSvc.Metrics.valueOf(metric); // IllegalArgumentException for unknown metrics
            return StockReportSvc.processMetrics(new String[]{"METRIC", ticker, metric, "0"});
        });
    }

    public static Set<String> metricNames() {
        Set<String> s = new LinkedHashSet<>();
        for (StockReportSvc.Metrics m : StockReportSvc.Metrics.values()) s.add(m.name());
        return s;
    }

    // ------------------------------------------------------------------ OHLC
    /** Range form: OHLC,fld,start,end,ticker. Values in dollars (legacy /100), adv raw. */
    public static Map<Integer, Double> ohlcRange(String fld, int start, int end, String ticker) throws Exception {
        return locked(() -> {
            requireReady();
            if (!StockReportSvc.sym2idx.containsKey(ticker)) throw new NotInUniverse(ticker);
            int e = (start == end) ? Dates.offset(end, 1) : end; // legacy treats start==end as the as-of form
            return StockReportSvc.processOHLC(new String[]{"OHLC", fld, "" + start, "" + e, ticker});
        });
    }

    /** As-of form: OHLC,fld,dt,dt,t1,t2,... -> values in request order (missing = 0.0). */
    public static List<Double> ohlcAsof(String fld, int dt, List<String> tickers) throws Exception {
        return locked(() -> {
            requireReady();
            String[] csv = new String[4 + tickers.size()];
            csv[0] = "OHLC"; csv[1] = fld; csv[2] = "" + dt; csv[3] = "" + dt;
            for (int i = 0; i < tickers.size(); i++) csv[4 + i] = tickers.get(i);
            Map<Integer, Double> res = StockReportSvc.processOHLC(csv);
            List<Double> out = new ArrayList<>();
            for (int i = 0; i < tickers.size(); i++) out.add(res.getOrDefault(i + 1, 0.0));
            return out;
        });
    }

    // ------------------------------------------------------------------ PRICE
    public static TreeMap<Integer, Double> series(String name, String kind) throws Exception {
        return locked(() -> {
            requireReady();
            if (!kind.equals("STK")) {
                StockFeat.PortBuilder b = StockFeat.builder.get(name);
                if (b == null) return new TreeMap<Integer, Double>();
                if (!kind.equals("EWT") && b.mw() == null) return new TreeMap<Integer, Double>(); // legacy NPE
            }
            return StockReportSvc.processTimeSeries(new String[]{"PRICE", name, kind});
        });
    }

    public static boolean isPortfolio(String name) throws Exception {
        return locked(() -> ready && StockFeat.builder.containsKey(name));
    }

    // ------------------------------------------------------------------ SHADOW
    /** Returns legacy SHADOW output lines: header then one CSV row per simulated day. */
    public static List<String> shadow(String ticker, int initdt, String optvol, String riskvol) throws Exception {
        return locked(() -> {
            requireReady();
            if (!Context.symbols.containsKey(ticker)) throw new NotInUniverse(ticker);
            String d = "" + initdt;
            String iso = d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8);
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            StockReportSvc.shadowPortfolio(new String[]{"SHADOW", ticker, iso,
                    optvol == null ? "" : optvol, riskvol == null ? "" : riskvol}, pw);
            pw.flush();
            List<String> lines = new ArrayList<>();
            for (String l : sw.toString().split("\\R")) if (!l.isBlank()) lines.add(l);
            return lines;
        });
    }

    // ------------------------------------------------------------------ jobs (callers hold no lock)
    /** STOCKINFO: legacy StockInfo.execute() -> out.csv. Returns the as-of date (Context.lastdt). */
    public static int runStockInfo() throws Exception {
        return locked(() -> { StockInfo.execute(); return Context.lastdt; });
    }

    /** STOCKFEAT: legacy StockFeat.execute() -> port.csv. Returns as-of date and the raw builder series. */
    public static int runStockFeat(Map<String, Map<String, TreeMap<Integer, Double>>> seriesOut) throws Exception {
        return locked(() -> {
            StockFeat.execute();
            for (Map.Entry<String, StockFeat.PortBuilder> e : StockFeat.builder.entrySet()) {
                Map<String, TreeMap<Integer, Double>> m = new LinkedHashMap<>();
                m.put("EW", e.getValue().ew().load(new TreeMap<Integer, Double>()));
                if (e.getValue().mw() != null) m.put("MW", e.getValue().mw().load(new TreeMap<Integer, Double>()));
                seriesOut.put(e.getKey(), m);
            }
            return Context.lastdt;
        });
    }

    /** MODEL: legacy TasksRunner ran StockModel.execute(true) (pred only). train=true runs train + pred. */
    public static int runModel(boolean train) throws Exception {
        return locked(() -> { StockModel.execute(!train); return Context.lastdt; });
    }

    /** OPTFEAT: TasksRunner.LoadStockOxFeatures = Context.init() + StockOxParser.executeFeatures(). */
    public static void runOptFeat() throws Exception {
        lockedRun(() -> { Context.init(); StockOxParser.executeFeatures(); });
    }

    /** OPTIONS: StockOxParser.execute() (network bound; touches no analytics statics). */
    public static void runOptions() throws Exception {
        StockOxParser.execute();
    }

    /** PRICES: StockParser.execute() (network bound; touches no analytics statics). */
    public static void runPrices() throws Exception {
        StockParser.execute();
    }

    /** SYMBOLS / SYMBOLS_FULL: TasksRunner.LoadSymbols(full), unchanged loop. */
    public static void runSymbols(boolean full) throws Exception {
        StockDB db;
        if (!StockParser.wGet(Const.path + "symbols.txt", "ftp://ftp.nasdaqtrader.com/symboldirectory/nasdaqtraded.txt"))
            throw new com.mystock.ingest.UpstreamException("could not download the NASDAQ symbol directory");
        int result = 100000, currcnt = 99999;
        while (currcnt < result) {
            result = currcnt;
            db = new StockDB();
            currcnt = StockParser.loadsymbols(db, full, Const.path + "symbols.txt", true);
            System.out.println("Iteration bad read count " + currcnt + " result " + result);
            try { Thread.sleep(5000); } catch (Exception e) { e.printStackTrace(); }
        }
    }

    /** TasksRunner.loadTickers (unchanged logic), under the analytics lock since it reads Context. */
    public static List<String> volTickers(StockVolDB db) throws Exception {
        return locked(() -> {
            if (Context.symbols == null) Context.init();
            List<String> tickers = new ArrayList<String>();
            Map<String, double[]> dbmap = null;
            try {
                dbmap = db.readall();
            } catch (Exception e) {
                e.printStackTrace();
            }
            for (String stk : Context.symbols.keySet()) {
                try {
                    double dmktcap = Double.parseDouble(Context.dim(stk, "MarketCapitalization"));
                    if (dmktcap > 0 && dmktcap < 20000000000.0) continue;
                    if (!dbmap.containsKey(stk)) tickers.add(stk);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            for (String stk : dbmap.keySet()) tickers.add(stk);
            return tickers;
        });
    }

    /** One LoadVol iteration body for a ticker (network, no lock): PVCache.mktstats + upsert. */
    public static void volUpdate(StockVolDB db, String stk) throws Exception {
        double[] result = PVCache.mktstats(stk);
        db.open();
        db.upsert(stk, result[0], result[1], result[2], result[3]);
        db.close();
    }

    // ------------------------------------------------------------------ options (StockOptionMsg is package-private)
    public record OptionRow(String contractId, String symbol, String type, int asof, int expiration, double strike,
                            double last, double mark, double bid, int bidSize, double ask, int askSize, long volume,
                            long openInterest, double iv, double delta, double gamma, double theta, double vega,
                            double rho) {}

    /** Fetches HISTORICAL_OPTIONS via legacy StockOptionMsg.generate and stores all rows. Returns row count. */
    public static int fetchAndStoreOptions(String ticker, double upxDollars) throws Exception {
        List<StockOptionMsg> msgs = new ArrayList<>();
        boolean ok = StockOptionMsg.generate(ticker, msgs);
        if (!ok || msgs.isEmpty()) return 0;
        OptionPriceDB odb = new OptionPriceDB();
        odb.open();
        try {
            for (StockOptionMsg m : msgs) {
                m.upx = (int) (upxDollars * 100);
                odb.upsert(m);
            }
        } finally {
            odb.close();
        }
        return msgs.size();
    }

    public static double[] quote(String ticker) {
        return PVCache.register(ticker);
    }

    /** Legacy BSOption price + greeks {price, delta, vega, theta, rho, gamma} (r = q = 5% as legacy). */
    public static double[] bs(double s, double k, double t, double vol, char type) {
        BSOption o = new BSOption(s, k, t, vol, type);
        double[] g = o.computeGreeks();
        return new double[]{o.computePrice(), g[BSOption.DELTA], g[BSOption.VEGA], g[BSOption.THETA], g[BSOption.RHO], g[BSOption.GAMMA]};
    }

    public static void setQuoteFallback(PVCache.Fallback f) { PVCache.fallback = f; }
}
