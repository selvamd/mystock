package com.mystock.api;

import com.mystock.api.Arrow.Table;
import com.mystock.api.Arrow.Type;
import com.mystock.db.Database;
import com.mystock.ingest.UpstreamException;
import com.mystock.jobs.JobRunner;
import com.mystock.legacy.Legacy;
import com.mystock.services.AuthService;
import com.mystock.services.FinancialsService;
import com.mystock.services.MarketDataService;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Registry of protocol operations (docs/PROTOCOL.md). */
public final class Ops {
    public static final String VERSION = "1.0.0";

    @FunctionalInterface
    public interface Handler { Reply handle(Args a) throws Exception; }

    private final Map<String, Handler> ops = new LinkedHashMap<>();
    private final JobRunner jobs;
    private final AuthService auth = new AuthService();
    private final MarketDataService market = new MarketDataService();
    private final FinancialsService financials = new FinancialsService();

    public static final String STOCK_COLS = "\"asof\", " + String.join(", ", quoteAll(com.mystock.jobs.CsvLoader.STOCK_COLS));
    public static final String PORT_COLS = "\"asof\", " + String.join(", ", quoteAll(com.mystock.jobs.CsvLoader.PORT_COLS));

    private static List<String> quoteAll(String[] a) {
        List<String> l = new ArrayList<>();
        for (String s : a) l.add('"' + s + '"');
        return l;
    }

    public Ops(JobRunner jobs) {
        this.jobs = jobs;
        Legacy.setQuoteFallback(Ops::quoteFallback);

        ops.put("ping", this::ping);
        ops.put("admin.status", this::adminStatus);
        ops.put("admin.run_job", this::runJob);
        ops.put("admin.reload", a -> { Legacy.reload(); return Reply.json(Map.of("reloaded", true)); });

        ops.put("screener.stocks", this::screenerStocks);
        ops.put("screener.portfolios", this::screenerPortfolios);

        ops.put("prices.history", this::pricesHistory);
        ops.put("prices.ohlc", this::pricesOhlc);
        ops.put("prices.asof", this::pricesAsof);
        ops.put("prices.series", this::pricesSeries);

        ops.put("metrics.series", this::metricsSeries);
        ops.put("metrics.batch", this::metricsBatch);

        ops.put("quote.live", this::quoteLive);
        ops.put("market.vix", this::marketVix);
        ops.put("options.chain", this::optionsChain);
        ops.put("fundamentals.statements", this::fundamentals);
        ops.put("news.list", this::news);

        ops.put("auth.login", this::login);
        ops.put("auth.logout", a -> { auth.logout(a.str("token")); return Reply.json(Map.of("ok", true)); });
        ops.put("trades.list", this::tradesList);
        ops.put("trades.book", this::tradesBook);
        ops.put("config.queries", a -> Reply.arrow(Arrow.sql("SELECT name, value FROM saved_queries ORDER BY name")));
        ops.put("config.reports", a -> Reply.arrow(Arrow.sql("SELECT \"function\", report, params FROM dynamic_reports ORDER BY \"function\", report")));
        ops.put("predictions.list", a -> Reply.arrow(Arrow.sql(
                "SELECT ticker, \"asof\", tgtpx, open, high, low, close FROM predictions ORDER BY \"asof\", ticker")));
        ops.put("predictions.add", this::predictionsAdd);

        ops.put("backtest.shadow", this::shadow);
    }

    public Set<String> names() { return ops.keySet(); }

    public Reply dispatch(String op, Args a) throws Exception {
        Handler h = ops.get(op);
        if (h == null) throw ApiException.badRequest("unknown op '" + op + "'");
        return h.handle(a);
    }

    // ------------------------------------------------------------------ helpers
    static LocalDate today() { return LocalDate.now(com.mystock.config.Config.get().tz); }

    static String iso(int ymd) { return ymd <= 0 ? null : Arrow.ymd(ymd).toString(); }

    /** Upper-cases and checks that the ticker exists in `symbols`. */
    static String ticker(Args a, String k) throws SQLException {
        String t = a.ticker(k);
        try (Connection c = Database.current().connect(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM symbols WHERE ticker = ?")) {
            ps.setString(1, t);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw ApiException.notFound("unknown ticker " + t);
            }
        }
        return t;
    }

    private String sessionUser(Args a) throws SQLException {
        String u = auth.user(a.optStr("token", null));
        if (u == null) throw ApiException.forbidden("missing, invalid or expired session token");
        return u;
    }

    private static Table series(TreeMap<Integer, Double> m) {
        Table t = new Table().col("asof", Type.DATE).col("value", Type.DOUBLE);
        for (Map.Entry<Integer, Double> e : m.entrySet()) t.row(e.getKey(), e.getValue());
        return t;
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Connection c = Database.current().connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    // ------------------------------------------------------------------ health / admin
    private Reply ping(Args a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pong", true);
        m.put("db_date", iso(Legacy.dbdate()));
        m.put("version", VERSION);
        return Reply.json(m);
    }

    private Reply adminStatus(Args a) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("db_date", iso(Legacy.dbdate()));
        m.put("symbols", scalarLong("SELECT count(*) FROM symbols"));
        List<Map<String, Object>> jl = new ArrayList<>();
        String sql = "SELECT job, status, started_at, finished_at, message FROM job_runs WHERE id IN (SELECT max(id) FROM job_runs GROUP BY job) ORDER BY job";
        try (Connection c = Database.current().connect(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> j = new LinkedHashMap<>();
                j.put("job", rs.getString(1));
                j.put("status", rs.getString(2));
                j.put("started_at", rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant().toString());
                j.put("finished_at", rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant().toString());
                j.put("message", rs.getString(5));
                jl.add(j);
            }
        }
        m.put("jobs", jl);
        return Reply.json(m);
    }

    private Reply runJob(Args a) {
        String job = a.str("job").trim().toUpperCase(Locale.ROOT);
        if (!JobRunner.API_JOBS.contains(job)) throw ApiException.badRequest("unknown job " + job + "; expected one of " + JobRunner.API_JOBS);
        jobs.submit(job);
        return Reply.json(Map.of("accepted", true));
    }

    // ------------------------------------------------------------------ screener
    private Reply screenerStocks(Args a) {
        Integer limit = a.optInt("limit");
        String sql = "SELECT " + STOCK_COLS + " FROM stock_metrics WHERE \"asof\" = (SELECT max(\"asof\") FROM stock_metrics) ORDER BY ticker";
        if (limit != null) {
            if (limit < 0) throw ApiException.badRequest("limit must be >= 0");
            return Reply.arrow(Arrow.sql(sql + " LIMIT ?", limit));
        }
        return Reply.arrow(Arrow.sql(sql));
    }

    private Reply screenerPortfolios(Args a) {
        String w = a.optStr("wgtype", null);
        String sql = "SELECT " + PORT_COLS + " FROM portfolio_metrics WHERE \"asof\" = (SELECT max(\"asof\") FROM portfolio_metrics)";
        if (w != null) {
            w = w.trim().toUpperCase(Locale.ROOT);
            if (!w.equals("EW") && !w.equals("MW")) throw ApiException.badRequest("wgtype must be EW or MW");
            return Reply.arrow(Arrow.sql(sql + " AND wgtype = ? ORDER BY name", w));
        }
        return Reply.arrow(Arrow.sql(sql + " ORDER BY name"));
    }

    // ------------------------------------------------------------------ prices
    private Reply pricesHistory(Args a) throws SQLException {
        String t = ticker(a, "ticker");
        return Reply.arrow(Arrow.sql("SELECT \"asof\", open, high, low, close, adjclose, volume FROM prices WHERE ticker = ? ORDER BY \"asof\"", t));
    }

    /** Legacy OHLC,<fld>,start,end,ticker for open/high/low/close/prev/adv merged into one table. */
    private Reply pricesOhlc(Args a) throws Exception {
        String t = ticker(a, "ticker");
        int start = a.has("start") ? a.date("start") : 20150101; // legacy client defaults
        int end = a.has("end") ? a.date("end") : 20990101;
        if (!Legacy.inUniverse(t)) {
            // Outside the legacy analytics universe legacy failed; serve the same adjustment from SQL.
            return Reply.arrow(Arrow.sql("SELECT \"asof\", open*adjclose/close AS open, high*adjclose/close AS high, low*adjclose/close AS low, " +
                    "adjclose AS close, prev, volume FROM (SELECT *, lag(adjclose) OVER (ORDER BY \"asof\") AS prev FROM prices WHERE ticker = ?) " +
                    "WHERE \"asof\" BETWEEN ? AND ? ORDER BY \"asof\"", t, Arrow.ymd(start), Arrow.ymd(Math.min(end, 99991231))));
        }
        Map<Integer, Double> close = Legacy.ohlcRange("CLOSE", start, end, t);
        Map<Integer, Double> open = Legacy.ohlcRange("OPEN", start, end, t);
        Map<Integer, Double> high = Legacy.ohlcRange("HIGH", start, end, t);
        Map<Integer, Double> low = Legacy.ohlcRange("LOW", start, end, t);
        Map<Integer, Double> prev = Legacy.ohlcRange("PREV", start, end, t);
        Map<Integer, Double> adv = Legacy.ohlcRange("ADV", start, end, t);
        Table tb = new Table().col("asof", Type.DATE).col("open", Type.DOUBLE).col("high", Type.DOUBLE).col("low", Type.DOUBLE)
                .col("close", Type.DOUBLE).col("prev", Type.DOUBLE).col("volume", Type.LONG);
        for (Map.Entry<Integer, Double> e : new TreeMap<>(close).entrySet()) {
            int d = e.getKey();
            if (d < start || d > end) continue;
            Double v = adv.get(d);
            tb.row(d, open.get(d), high.get(d), low.get(d), e.getValue(), prev.get(d), v == null ? null : v.longValue());
        }
        return Reply.arrow(tb);
    }

    private Reply pricesAsof(Args a) throws Exception {
        int dt = a.date("date");
        String f = a.str("field").trim().toLowerCase(Locale.ROOT);
        String fld = switch (f) {
            case "open", "high", "low", "close", "prev" -> f.toUpperCase(Locale.ROOT);
            case "volume", "adv" -> "ADV";
            default -> throw ApiException.badRequest("field must be one of open/high/low/close/prev/volume");
        };
        List<String> tickers = new ArrayList<>();
        for (String s : a.list("tickers")) tickers.add(s.trim().toUpperCase(Locale.ROOT));
        List<Double> vals = Legacy.ohlcAsof(fld, dt, tickers);
        Table tb = new Table().col("ticker", Type.STRING).col("value", Type.DOUBLE);
        for (int i = 0; i < tickers.size(); i++) tb.row(tickers.get(i), vals.get(i));
        return Reply.arrow(tb);
    }

    private Reply pricesSeries(Args a) throws Exception {
        String kind = a.str("kind").trim().toUpperCase(Locale.ROOT);
        if (!List.of("STK", "EWT", "MWT", "AWT").contains(kind)) throw ApiException.badRequest("kind must be STK, EWT, MWT or AWT");
        String name;
        if (kind.equals("STK")) name = ticker(a, "ticker");
        else {
            name = a.ticker("ticker");
            if (!Legacy.isPortfolio(name)) {
                if (!Legacy.ready()) throw new Legacy.NoMarketData();
                throw ApiException.notFound("unknown portfolio " + name);
            }
        }
        return Reply.arrow(series(Legacy.series(name, kind)));
    }

    // ------------------------------------------------------------------ metrics
    private static String metricName(String m) {
        String u = m.trim().toUpperCase(Locale.ROOT);
        if (!Legacy.metricNames().contains(u)) throw ApiException.badRequest("unknown metric " + m);
        return u;
    }

    private Reply metricsSeries(Args a) throws Exception {
        String t = ticker(a, "ticker");
        return Reply.arrow(series(Legacy.metric(t, metricName(a.str("metric")))));
    }

    private Reply metricsBatch(Args a) throws Exception {
        List<String> tickers = new ArrayList<>();
        for (String s : a.list("tickers")) {
            String t = s.trim().toUpperCase(Locale.ROOT);
            tickers.add(t);
        }
        for (String t : tickers) {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("ticker", t);
            ticker(new Args(o), "ticker");
        }
        List<String> metrics = new ArrayList<>();
        for (String m : a.list("metrics")) metrics.add(metricName(m));
        Integer since = a.optDate("since");
        Table tb = new Table().col("ticker", Type.STRING).col("metric", Type.STRING).col("asof", Type.DATE).col("value", Type.DOUBLE);
        for (String t : tickers)
            for (String m : metrics)
                for (Map.Entry<Integer, Double> e : Legacy.metric(t, m).entrySet())
                    if (since == null || e.getKey() >= since) tb.row(t, m, e.getKey(), e.getValue());
        return Reply.arrow(tb);
    }

    // ------------------------------------------------------------------ live market data
    /** Offline fallback for PVCache: last close, stock_vol IV (else stock_metrics optvol/histvol), earnings. */
    static double[] quoteFallback(String sym) {
        double[] r = new double[3];
        try (Connection c = Database.current().connect()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT close FROM prices WHERE ticker = ? ORDER BY \"asof\" DESC LIMIT 1")) {
                ps.setString(1, sym);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) r[0] = rs.getDouble(1); }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT impliedvol, next_earnings_date FROM stock_vol WHERE ticker = ?")) {
                ps.setString(1, sym);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) { r[1] = rs.getDouble(1); r[2] = rs.getDouble(2); } }
            }
            if (r[1] == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT optvol, histvol FROM stock_metrics WHERE ticker = ? ORDER BY \"asof\" DESC LIMIT 1")) {
                    ps.setString(1, sym);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            double ov = rs.getDouble(1), hv = rs.getDouble(2);
                            r[1] = (Double.isFinite(ov) && ov > 0) ? ov : (Double.isFinite(hv) ? hv : 0);
                        }
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return r;
    }

    private Reply quoteLive(Args a) throws SQLException {
        String t = ticker(a, "ticker");
        double[] d = Legacy.quote(t);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticker", t);
        m.put("spot_price", d[0]);
        m.put("spot_vol", d[1]);
        String earn = null;
        try { if (d[2] > 19000101) earn = Arrow.ymd((int) d[2]).toString(); } catch (Exception ignored) { }
        m.put("next_earnings", earn);
        return Reply.json(m);
    }

    private Reply marketVix(Args a) {
        Integer days = a.optInt("days");
        List<MarketDataService.VixRow> rows;
        try {
            rows = market.vix();
        } catch (UpstreamException e) {
            rows = List.of(); // offline: empty stream (allowed by the integration brief)
        }
        int from = (days == null) ? 0 : Math.max(0, rows.size() - days);
        Table t = new Table().col("date", Type.DATE).col("vix", Type.DOUBLE).col("skew", Type.DOUBLE);
        for (int i = from; i < rows.size(); i++) t.row(rows.get(i).date(), rows.get(i).vix(), rows.get(i).skew());
        return Reply.arrow(t);
    }

    private static final String CHAIN_SQL = "SELECT contract_id, symbol, type, \"asof\", expiration, strike, last, mark, bid, bid_size, ask, " +
            "ask_size, volume, open_interest, implied_volatility, delta, gamma, theta, vega, rho, upx FROM option_quotes " +
            "WHERE symbol = ? AND \"asof\" = (SELECT max(\"asof\") FROM option_quotes WHERE symbol = ?) ORDER BY expiration, strike, type";

    private Reply optionsChain(Args a) throws Exception {
        String t = ticker(a, "ticker");
        boolean refresh = a.bool("refresh", false);
        LocalDate latest = null;
        try (Connection c = Database.current().connect();
             PreparedStatement ps = c.prepareStatement("SELECT max(\"asof\") FROM option_quotes WHERE symbol = ?")) {
            ps.setString(1, t);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next() && rs.getDate(1) != null) latest = rs.getDate(1).toLocalDate(); }
        }
        if (refresh || latest == null || latest.isBefore(today())) {
            try {
                double upx = quoteFallback(t) == null ? 0 : quoteFallback(t)[0];
                int n = Legacy.fetchAndStoreOptions(t, upx);
                if (n == 0 && latest == null) throw ApiException.upstream("no option data returned for " + t);
            } catch (UpstreamException e) {
                if (latest == null) throw e; // nothing cached: report UPSTREAM; otherwise serve the stale snapshot
            }
        }
        return Reply.arrow(Arrow.sql(CHAIN_SQL, t, t));
    }

    private Reply fundamentals(Args a) throws Exception {
        String t = ticker(a, "ticker");
        if (!financials.fresh(t)) {
            try {
                financials.refresh(t);
            } catch (UpstreamException e) {
                if (!financials.hasAny(t)) throw e; // serve stale cache when the provider fails
            }
        }
        return Reply.arrow(Arrow.sql("SELECT statement, fiscal_date, period, field, value FROM financials WHERE ticker = ? " +
                "ORDER BY statement, period, fiscal_date DESC, field", t));
    }

    private Reply news(Args a) throws Exception {
        String t = ticker(a, "ticker");
        Integer limit = a.optInt("limit");
        int lim = limit == null ? 10 : Math.max(1, Math.min(limit, 1000));
        Table tb = new Table().col("published", Type.TIMESTAMP).col("author", Type.STRING).col("url", Type.STRING)
                .col("title", Type.STRING).col("sentiment", Type.STRING).col("sentiment_reasoning", Type.STRING);
        for (MarketDataService.NewsRow r : market.news(t, lim)) {
            Long us = r.published() == null ? null : r.published().getEpochSecond() * 1_000_000L + r.published().getNano() / 1000;
            tb.row(us, r.author(), r.url(), r.title(), r.sentiment(), r.reasoning());
        }
        return Reply.arrow(tb);
    }

    // ------------------------------------------------------------------ auth / trades / user data
    private Reply login(Args a) throws SQLException {
        AuthService.Session s = auth.login(a.str("login"), a.str("password"));
        if (s == null) throw new ApiException("UNAUTHORIZED", "invalid login or password");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", s.token());
        m.put("login", s.login());
        m.put("expires_at", s.expiresAt().toString());
        return Reply.json(m);
    }

    private Reply tradesList(Args a) throws SQLException {
        String u = sessionUser(a);
        return Reply.arrow(Arrow.sql("SELECT trade_id, login, portfolio, position, buysell, legsym, px, qty, tradedt, upx, uvol " +
                "FROM trades WHERE login = ? ORDER BY trade_id", u));
    }

    private static long lastTradeId = 0;

    /** Legacy id: (today % 1000000) concatenated with currentTimeMillis, made strictly increasing. */
    private static synchronized long newTradeId() {
        long id = Long.parseLong((com.mystock.legacy.Dates.today() % 1000000) + "" + System.currentTimeMillis());
        if (id <= lastTradeId) id = lastTradeId + 1;
        lastTradeId = id;
        return id;
    }

    private Reply tradesBook(Args a) throws Exception {
        String u = sessionUser(a);
        String portfolio = a.str("portfolio"), position = a.str("position"), buysell = a.str("buysell"), legsym = a.str("legsym");
        double px = a.dbl("px");
        int qty = a.integer("qty");
        long id = newTradeId();
        try (Connection c = Database.current().connect()) {
            if (qty == 0) {
                // Legacy TRADE: qty 0 deletes every trade of (login, portfolio, position, legsym)
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM trades WHERE login = ? AND portfolio = ? AND position = ? AND legsym = ?")) {
                    ps.setString(1, u); ps.setString(2, portfolio); ps.setString(3, position); ps.setString(4, legsym);
                    ps.executeUpdate();
                }
            } else {
                Double upx = a.optDbl("upx"), uvol = a.optDbl("uvol");
                LocalDate tradedt = a.has("tradedt") ? Arrow.ymd(a.date("tradedt")) : today();
                if (upx == null || uvol == null) {
                    double[] d = Legacy.quote(legsym.split("@")[0].toUpperCase(Locale.ROOT));
                    if (upx == null) upx = d[0];
                    if (uvol == null) uvol = d[1];
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO trades (trade_id, login, portfolio, position, buysell, legsym, px, qty, tradedt, upx, uvol) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setLong(1, id); ps.setString(2, u); ps.setString(3, portfolio); ps.setString(4, position);
                    ps.setString(5, buysell); ps.setString(6, legsym); ps.setDouble(7, px); ps.setInt(8, qty);
                    ps.setDate(9, java.sql.Date.valueOf(tradedt)); ps.setDouble(10, upx); ps.setDouble(11, uvol);
                    ps.executeUpdate();
                }
            }
        }
        return Reply.json(Map.of("trade_id", id));
    }

    private Reply predictionsAdd(Args a) throws SQLException {
        String t = ticker(a, "ticker");
        double tgt = a.dbl("tgtpx"), mkt = a.dbl("mktpx");
        try (Connection c = Database.current().connect();
             PreparedStatement ps = c.prepareStatement("INSERT INTO predictions (ticker, \"asof\", tgtpx, open, high, low, close) VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, t); ps.setDate(2, java.sql.Date.valueOf(today())); ps.setDouble(3, tgt);
            ps.setDouble(4, mkt); ps.setDouble(5, mkt); ps.setDouble(6, mkt); ps.setDouble(7, mkt);
            ps.executeUpdate();
        }
        return Reply.json(Map.of("ok", true));
    }

    // ------------------------------------------------------------------ backtest
    private Reply shadow(Args a) throws Exception {
        String t = ticker(a, "ticker");
        int initdt = a.date("initdt");
        Double ov = a.optDbl("optvol"), rv = a.optDbl("riskvol");
        if ((ov == null) != (rv == null)) throw ApiException.badRequest("give both optvol and riskvol, or neither");
        List<String> lines = Legacy.shadow(t, initdt, ov == null ? null : ov.toString(), rv == null ? null : rv.toString());
        if (lines.isEmpty()) throw new IllegalStateException("backtest produced no output");
        String[] head = lines.get(0).split(",");
        Table tb = new Table().col("ymd", Type.DATE);
        for (String h : head) tb.col(h.trim(), Type.DOUBLE);
        int dt = initdt;
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split(",");
            Object[] row = new Object[head.length + 1];
            row[0] = dt; // ICBackTest.execute logs one row per calendar day starting at initdt
            for (int j = 0; j < head.length; j++) {
                try { row[j + 1] = j < p.length ? Double.parseDouble(p[j].trim()) : null; } catch (NumberFormatException e) { row[j + 1] = null; }
            }
            tb.row(row);
            dt = com.mystock.legacy.Dates.offset(dt, 1);
        }
        return Reply.arrow(tb);
    }
}
