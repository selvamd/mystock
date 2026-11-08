package com.mystock.tools;

import com.mystock.config.Config;
import com.mystock.db.Database;
import com.mystock.jobs.JobRunner;
import com.mystock.legacy.Legacy;
import com.mystock.services.AuthService;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.sql.*;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Deterministic, network-free demo dataset: ~68 symbols (ETFs incl. RSP/SPY + large caps across sectors),
 * seeded-GBM daily OHLCV on weekdays from 2015-01-02 to the most recent weekday, overviews, stock_vol,
 * option quotes for SPY/AAPL/TSLA, financials, user demo/demo, saved queries, dynamic reports, predictions;
 * then runs STOCKINFO and STOCKFEAT so the screeners have data.
 */
public final class SeedDemo {
    static final long SEED = 20150102L;

    record Spec(String ticker, String name, String sector, String industry, boolean etf, double startPx,
                double beta, double vol, double drift, double shares, double pe, double divYield) {}

    static final List<Spec> SPECS = new ArrayList<>();

    static void etf(String t, String name, double px, double beta, double vol) {
        SPECS.add(new Spec(t, name, null, null, true, px, beta, vol, 0.0, 0, 0, 0.012));
    }

    static void stk(String t, String name, String sector, String ind, double px, double beta, double vol, double drift, double sharesB, double pe, double dy) {
        SPECS.add(new Spec(t, name, sector, ind, false, px, beta, vol, drift, sharesB * 1e9, pe, dy));
    }

    static {
        etf("RSP", "Invesco S&P 500 Equal Weight ETF", 78, 1.0, 0.002);
        etf("SPY", "SPDR S&P 500 ETF Trust", 205, 1.0, 0.001);
        etf("QQQ", "Invesco QQQ Trust", 102, 1.2, 0.004);
        etf("IWM", "iShares Russell 2000 ETF", 119, 1.15, 0.006);
        etf("DIA", "SPDR Dow Jones Industrial Average ETF", 178, 0.9, 0.003);
        etf("TLT", "iShares 20+ Year Treasury Bond ETF", 126, -0.3, 0.008);
        etf("GLD", "SPDR Gold Shares", 114, 0.05, 0.009);
        etf("XLK", "Technology Select Sector SPDR Fund", 41, 1.2, 0.005);
        etf("XLF", "Financial Select Sector SPDR Fund", 24, 1.1, 0.006);
        etf("XLE", "Energy Select Sector SPDR Fund", 75, 1.0, 0.012);
        etf("XLV", "Health Care Select Sector SPDR Fund", 68, 0.8, 0.005);
        etf("XLP", "Consumer Staples Select Sector SPDR Fund", 48, 0.6, 0.004);
        etf("XLU", "Utilities Select Sector SPDR Fund", 47, 0.5, 0.006);
        etf("XLB", "Materials Select Sector SPDR Fund", 48, 1.05, 0.006);
        etf("XLI", "Industrial Select Sector SPDR Fund", 56, 1.05, 0.005);
        etf("XLRE", "Real Estate Select Sector SPDR Fund", 32, 0.85, 0.007);
        etf("XLY", "Consumer Discretionary Select Sector SPDR Fund", 69, 1.15, 0.006);
        etf("XLC", "Communication Services Select Sector SPDR Fund", 50, 1.1, 0.006);
        etf("VNQ", "Vanguard Real Estate ETF", 81, 0.9, 0.007);
        etf("SMH", "VanEck Semiconductor ETF", 52, 1.4, 0.009);
        etf("XBI", "SPDR S&P Biotech ETF", 64, 1.2, 0.013);
        etf("KRE", "SPDR S&P Regional Banking ETF", 40, 1.2, 0.010);

        stk("AAPL", "Apple Inc", "TECHNOLOGY", "CONSUMER ELECTRONICS", 27, 1.2, 0.012, 0.0006, 15.4, 29, 0.005);
        stk("MSFT", "Microsoft Corporation", "TECHNOLOGY", "SERVICES-PREPACKAGED SOFTWARE", 46, 1.1, 0.011, 0.0006, 7.4, 33, 0.008);
        stk("GOOG", "Alphabet Inc Class C", "COMMUNICATION SERVICES", "INTERNET CONTENT & INFORMATION", 26, 1.1, 0.013, 0.0005, 12.2, 24, 0.004);
        stk("AMZN", "Amazon.com Inc", "CONSUMER CYCLICAL", "RETAIL-CATALOG & MAIL-ORDER HOUSES", 15, 1.25, 0.015, 0.0006, 10.5, 42, 0.0);
        stk("NVDA", "NVIDIA Corporation", "TECHNOLOGY", "SEMICONDUCTORS & RELATED DEVICES", 0.5, 1.7, 0.022, 0.0013, 24.4, 55, 0.0003);
        stk("META", "Meta Platforms Inc", "COMMUNICATION SERVICES", "SERVICES-COMPUTER PROGRAMMING", 78, 1.3, 0.018, 0.0005, 2.5, 27, 0.003);
        stk("TSLA", "Tesla Inc", "CONSUMER CYCLICAL", "MOTOR VEHICLES & PASSENGER CAR BODIES", 14, 1.9, 0.028, 0.0008, 3.2, 70, 0.0);
        stk("AVGO", "Broadcom Inc", "TECHNOLOGY", "SEMICONDUCTORS & RELATED DEVICES", 10, 1.3, 0.017, 0.0009, 4.7, 35, 0.015);
        stk("AMD", "Advanced Micro Devices Inc", "TECHNOLOGY", "SEMICONDUCTORS & RELATED DEVICES", 2.7, 1.8, 0.026, 0.0011, 1.6, 45, 0.0);
        stk("ORCL", "Oracle Corporation", "TECHNOLOGY", "SERVICES-PREPACKAGED SOFTWARE", 44, 1.0, 0.013, 0.0005, 2.8, 30, 0.012);
        stk("CRM", "Salesforce Inc", "TECHNOLOGY", "SERVICES-PREPACKAGED SOFTWARE", 74, 1.2, 0.017, 0.0004, 0.97, 40, 0.004);
        stk("ADBE", "Adobe Inc", "TECHNOLOGY", "SERVICES-PREPACKAGED SOFTWARE", 73, 1.2, 0.016, 0.0004, 0.44, 35, 0.0);
        stk("INTC", "Intel Corporation", "TECHNOLOGY", "SEMICONDUCTORS & RELATED DEVICES", 36, 1.0, 0.016, -0.0001, 4.3, 20, 0.02);
        stk("CSCO", "Cisco Systems Inc", "TECHNOLOGY", "COMPUTER COMMUNICATIONS EQUIPMENT", 27, 0.9, 0.011, 0.0003, 4.0, 17, 0.03);
        stk("NFLX", "Netflix Inc", "COMMUNICATION SERVICES", "SERVICES-VIDEO TAPE RENTAL", 49, 1.3, 0.022, 0.0007, 0.43, 45, 0.0);
        stk("DIS", "Walt Disney Co", "COMMUNICATION SERVICES", "SERVICES-MISC. AMUSEMENT & RECREATION", 94, 1.1, 0.014, 0.0001, 1.8, 30, 0.006);
        stk("JPM", "JPMorgan Chase & Co", "FINANCIAL SERVICES", "NATIONAL COMMERCIAL BANKS", 62, 1.1, 0.013, 0.0005, 2.8, 12, 0.025);
        stk("BAC", "Bank of America Corp", "FINANCIAL SERVICES", "NATIONAL COMMERCIAL BANKS", 17, 1.3, 0.016, 0.0004, 7.8, 13, 0.022);
        stk("GS", "Goldman Sachs Group Inc", "FINANCIAL SERVICES", "SECURITY BROKERS, DEALERS & FLOTATION COMPANIES", 190, 1.3, 0.015, 0.0005, 0.31, 15, 0.02);
        stk("MS", "Morgan Stanley", "FINANCIAL SERVICES", "SECURITY BROKERS, DEALERS & FLOTATION COMPANIES", 38, 1.3, 0.016, 0.0005, 1.6, 16, 0.03);
        stk("V", "Visa Inc", "FINANCIAL SERVICES", "SERVICES-BUSINESS SERVICES, NEC", 65, 0.95, 0.012, 0.0005, 2.0, 30, 0.007);
        stk("MA", "Mastercard Inc", "FINANCIAL SERVICES", "SERVICES-BUSINESS SERVICES, NEC", 86, 1.0, 0.012, 0.0006, 0.93, 35, 0.005);
        stk("XOM", "Exxon Mobil Corp", "ENERGY", "PETROLEUM REFINING", 92, 0.9, 0.015, 0.0001, 4.0, 13, 0.035);
        stk("CVX", "Chevron Corp", "ENERGY", "PETROLEUM REFINING", 110, 0.95, 0.015, 0.0001, 1.8, 14, 0.04);
        stk("COP", "ConocoPhillips", "ENERGY", "CRUDE PETROLEUM & NATURAL GAS", 68, 1.1, 0.019, 0.0002, 1.2, 12, 0.03);
        stk("UNH", "UnitedHealth Group Inc", "HEALTHCARE", "HOSPITAL & MEDICAL SERVICE PLANS", 118, 0.8, 0.013, 0.0004, 0.92, 22, 0.015);
        stk("JNJ", "Johnson & Johnson", "HEALTHCARE", "PHARMACEUTICAL PREPARATIONS", 104, 0.6, 0.010, 0.0002, 2.4, 16, 0.03);
        stk("PFE", "Pfizer Inc", "HEALTHCARE", "PHARMACEUTICAL PREPARATIONS", 31, 0.7, 0.012, 0.0, 5.7, 12, 0.05);
        stk("LLY", "Eli Lilly and Co", "HEALTHCARE", "PHARMACEUTICAL PREPARATIONS", 69, 0.6, 0.015, 0.0010, 0.95, 60, 0.008);
        stk("ABBV", "AbbVie Inc", "HEALTHCARE", "PHARMACEUTICAL PREPARATIONS", 65, 0.7, 0.013, 0.0004, 1.77, 20, 0.035);
        stk("MRK", "Merck & Co Inc", "HEALTHCARE", "PHARMACEUTICAL PREPARATIONS", 61, 0.6, 0.011, 0.0003, 2.5, 18, 0.028);
        stk("KO", "Coca-Cola Co", "CONSUMER DEFENSIVE", "BEVERAGES", 42, 0.6, 0.009, 0.0002, 4.3, 25, 0.03);
        stk("PEP", "PepsiCo Inc", "CONSUMER DEFENSIVE", "BEVERAGES", 94, 0.6, 0.009, 0.0002, 1.37, 26, 0.03);
        stk("PG", "Procter & Gamble Co", "CONSUMER DEFENSIVE", "SOAP, DETERGENTS, CLEANG PREPARATIONS", 90, 0.5, 0.009, 0.0002, 2.35, 25, 0.025);
        stk("WMT", "Walmart Inc", "CONSUMER DEFENSIVE", "RETAIL-VARIETY STORES", 28, 0.5, 0.011, 0.0005, 8.0, 30, 0.015);
        stk("COST", "Costco Wholesale Corp", "CONSUMER DEFENSIVE", "RETAIL-VARIETY STORES", 142, 0.7, 0.011, 0.0007, 0.44, 45, 0.006);
        stk("HD", "Home Depot Inc", "CONSUMER CYCLICAL", "RETAIL-LUMBER & OTHER BUILDING MATERIALS DEALERS", 114, 1.0, 0.013, 0.0004, 1.0, 23, 0.024);
        stk("MCD", "McDonald's Corp", "CONSUMER CYCLICAL", "RETAIL-EATING PLACES", 94, 0.6, 0.010, 0.0004, 0.72, 25, 0.023);
        stk("NKE", "Nike Inc", "CONSUMER CYCLICAL", "RUBBER & PLASTICS FOOTWEAR", 48, 1.0, 0.015, 0.0001, 1.5, 28, 0.015);
        stk("CAT", "Caterpillar Inc", "INDUSTRIALS", "CONSTRUCTION, MINING & MATERIALS HANDLING MACHINERY", 91, 1.1, 0.015, 0.0006, 0.49, 17, 0.017);
        stk("BA", "Boeing Co", "INDUSTRIALS", "AIRCRAFT", 130, 1.4, 0.019, 0.0001, 0.75, 40, 0.0);
        stk("HON", "Honeywell International Inc", "INDUSTRIALS", "AIRCRAFT ENGINES & ENGINE PARTS", 100, 1.0, 0.011, 0.0003, 0.65, 22, 0.02);
        stk("GE", "GE Aerospace", "INDUSTRIALS", "AIRCRAFT ENGINES & ENGINE PARTS", 60, 1.2, 0.017, 0.0005, 1.08, 30, 0.006);
        stk("PLD", "Prologis Inc", "REAL ESTATE", "REAL ESTATE INVESTMENT TRUSTS", 43, 1.0, 0.014, 0.0004, 0.93, 35, 0.03);
        stk("AMT", "American Tower Corp", "REAL ESTATE", "REAL ESTATE INVESTMENT TRUSTS", 99, 0.8, 0.014, 0.0003, 0.47, 40, 0.03);
        stk("LIN", "Linde plc", "BASIC MATERIALS", "INDUSTRIAL INORGANIC CHEMICALS", 93, 0.9, 0.012, 0.0005, 0.48, 32, 0.012);
    }

    /** Splits (adjusted series stays continuous; raw prices before the date are multiplied). */
    static final Map<String, Object[]> SPLITS = Map.of(
            "AAPL", new Object[]{LocalDate.of(2020, 8, 31), 4.0},
            "NVDA", new Object[]{LocalDate.of(2024, 6, 10), 10.0},
            "TSLA", new Object[]{LocalDate.of(2022, 8, 25), 3.0});

    static LocalDate lastWeekday(LocalDate d) {
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.minusDays(1);
        return d;
    }

    public static List<LocalDate> tradingDays(LocalDate end) {
        List<LocalDate> l = new ArrayList<>();
        for (LocalDate d = LocalDate.of(2015, 1, 2); !d.isAfter(end); d = d.plusDays(1))
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) l.add(d);
        return l;
    }

    /** Legacy truncation of a price string to int cents: (int)(px*100). */
    static int cents(double px) { return (int) (px * 100); }

    public void run() throws Exception {
        LocalDate end = lastWeekday(LocalDate.now(Config.get().tz));
        run(end, true);
    }

    public void run(LocalDate end, boolean computeMetrics) throws Exception {
        List<LocalDate> days = tradingDays(end);
        Random mrng = new Random(SEED);
        double[] mkt = new double[days.size()];
        for (int i = 0; i < mkt.length; i++) {
            double shock = (mrng.nextDouble() < 0.01) ? mrng.nextGaussian() * 0.03 : 0;
            mkt[i] = 0.00035 + mrng.nextGaussian() * 0.009 + shock;
        }
        try (Connection c = Database.current().connect(); Statement st = c.createStatement()) {
            for (String t : List.of("symbols", "price_status", "prices", "stock_vol", "option_quotes", "financials", "saved_queries",
                    "dynamic_reports", "predictions", "stock_metrics", "portfolio_metrics", "portfolio_series", "stock_features", "trades", "sessions"))
                st.execute("DELETE FROM " + t);
        }
        Map<String, double[]> lastRaw = new HashMap<>();
        try (Connection c = Database.current().connect();
             DuckDBAppender ap = ((DuckDBConnection) c).createAppender(DuckDBConnection.DEFAULT_SCHEMA, "prices")) {
            for (Spec s : SPECS) {
                Random r = new Random(SEED ^ s.ticker().hashCode());
                double adj = s.startPx();
                double[] adjs = new double[days.size()];
                for (int i = 0; i < days.size(); i++) {
                    double ret = s.beta() * mkt[i] + r.nextGaussian() * s.vol() + s.drift();
                    adj *= Math.exp(ret);
                    adjs[i] = adj;
                }
                double baseVol = s.etf() ? 2e7 : Math.max(2e6, s.shares() * 0.004);
                double prevClose = adjs[0];
                for (int i = 0; i < days.size(); i++) {
                    LocalDate d = days.get(i);
                    double split = 1.0;
                    Object[] sp = SPLITS.get(s.ticker());
                    if (sp != null && d.isBefore((LocalDate) sp[0])) split = (Double) sp[1];
                    double years = ChronoUnit.DAYS.between(d, end) / 365.0;
                    double div = Math.pow(1 + s.divYield(), years); // dividend adjustment: older raw prices higher
                    double closeAdj = adjs[i];
                    double openAdj = prevClose * Math.exp(r.nextGaussian() * s.vol() * 0.3);
                    double hiAdj = Math.max(openAdj, closeAdj) * (1 + Math.abs(r.nextGaussian()) * s.vol() * 0.5);
                    double loAdj = Math.min(openAdj, closeAdj) * (1 - Math.abs(r.nextGaussian()) * s.vol() * 0.5);
                    double f = split * div;
                    int o = cents(openAdj * f), h = cents(hiAdj * f), l = cents(loAdj * f), cl = cents(closeAdj * f), ac = cents(closeAdj);
                    long vol = (long) (baseVol * Math.exp(r.nextGaussian() * 0.35));
                    ap.beginRow();
                    ap.append(s.ticker());
                    ap.append(d);
                    ap.append(o / 100.0); ap.append(h / 100.0); ap.append(l / 100.0); ap.append(cl / 100.0); ap.append(ac / 100.0);
                    ap.append(vol);
                    ap.endRow();
                    prevClose = closeAdj;
                    if (i == days.size() - 1) lastRaw.put(s.ticker(), new double[]{cl / 100.0, ac});
                }
            }
        }
        insertSymbols(lastRaw, end);
        insertVol(lastRaw, end);
        insertOptions(days, end);
        insertFinancials(end);
        insertUserData(end);
        System.out.println("seeded " + SPECS.size() + " symbols, " + days.size() + " trading days " + days.get(0) + ".." + end);
        if (computeMetrics) {
            try (JobRunner jr = new JobRunner()) {
                Legacy.reload();
                for (String job : List.of("STOCKINFO", "STOCKFEAT")) {
                    if (!jr.runNow(job)) throw new IllegalStateException(job + " failed during seed-demo");
                }
            }
            try (Connection c = Database.current().connect(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT (SELECT count(*) FROM stock_metrics), (SELECT count(*) FROM portfolio_metrics)")) {
                rs.next();
                System.out.println("stock_metrics rows: " + rs.getLong(1) + ", portfolio_metrics rows: " + rs.getLong(2));
            }
        }
    }

    static final String[] SYMBOL_COLS = {"AssetType", "Name", "Description", "Exchange", "Currency", "Country", "Sector", "Industry",
            "MarketCapitalization", "EBITDA", "PERatio", "BookValue", "DividendYield", "EPS", "RevenueTTM", "GrossProfitTTM", "SharesOutstanding"};

    private void insertSymbols(Map<String, double[]> lastRaw, LocalDate end) throws SQLException {
        String sql = "INSERT INTO symbols (symid, ticker, " + String.join(", ", SYMBOL_COLS) + ", etlstatus) VALUES (?, ?" + ",?".repeat(SYMBOL_COLS.length) + ", 1)";
        try (Connection c = Database.current().connect(); PreparedStatement ps = c.prepareStatement(sql);
             PreparedStatement st = c.prepareStatement("INSERT INTO price_status VALUES (?, ?, ?)")) {
            int symid = 0;
            for (Spec s : SPECS) {
                symid++;
                double px = lastRaw.get(s.ticker())[0];
                ps.setInt(1, symid);
                ps.setString(2, s.ticker());
                if (s.etf()) {
                    // NASDAQ symbol-directory path in legacy: only Name/Description for ETFs
                    String[] v = {"ETF", s.name(), s.name(), "NYSE ARCA", "USD", "USA", null, null, null, null, null, null, null, null, null, null, null};
                    for (int i = 0; i < v.length; i++) ps.setString(3 + i, v[i]);
                } else {
                    double mcap = Math.floor(s.shares() * px);
                    double eps = px / s.pe();
                    double rev = mcap / 6.0, gp = rev * 0.45, ebitda = rev * 0.3, book = mcap / 8.0 / s.shares();
                    String[] v = {"Common Stock", s.name(), s.name() + " (demo data)", "NASDAQ", "USD", "USA", s.sector(), s.industry(),
                            String.format(Locale.US, "%.0f", mcap), String.format(Locale.US, "%.0f", ebitda), String.format(Locale.US, "%.2f", s.pe()),
                            String.format(Locale.US, "%.2f", book), String.format(Locale.US, "%.4f", s.divYield()), String.format(Locale.US, "%.2f", eps),
                            String.format(Locale.US, "%.0f", rev), String.format(Locale.US, "%.0f", gp), String.format(Locale.US, "%.0f", s.shares())};
                    for (int i = 0; i < v.length; i++) ps.setString(3 + i, v[i]);
                }
                ps.addBatch();
                st.setInt(1, symid);
                st.setInt(2, end.getYear() * 10000 + end.getMonthValue() * 100 + end.getDayOfMonth());
                st.setInt(3, (int) Math.round(lastRaw.get(s.ticker())[1] * 100) / 1); // adjclose cents
                st.addBatch();
            }
            ps.executeBatch();
            st.executeBatch();
        }
    }

    private void insertVol(Map<String, double[]> lastRaw, LocalDate end) throws SQLException {
        try (Connection c = Database.current().connect(); PreparedStatement ps = c.prepareStatement("INSERT INTO stock_vol VALUES (?,?,?,?,?,?)")) {
            long now = System.currentTimeMillis();
            for (Spec s : SPECS) {
                if (s.etf()) continue;
                Random r = new Random(SEED * 7 + s.ticker().hashCode());
                LocalDate earn = end.plusDays(5 + r.nextInt(85));
                double px = lastRaw.get(s.ticker())[0];
                ps.setString(1, s.ticker());
                ps.setDouble(2, earn.getYear() * 10000 + earn.getMonthValue() * 100 + earn.getDayOfMonth());
                ps.setDouble(3, s.pe());
                ps.setDouble(4, s.shares() * px / 1e6); // legacy mktstats unit: millions
                ps.setDouble(5, Math.round(s.vol() * Math.sqrt(252) * 100 * (1.05 + r.nextDouble() * 0.2) * 100) / 100.0);
                ps.setLong(6, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertOptions(List<LocalDate> days, LocalDate end) throws SQLException {
        String sql = "INSERT INTO option_quotes VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = Database.current().connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (String t : List.of("SPY", "AAPL", "TSLA")) {
                Spec s = SPECS.stream().filter(x -> x.ticker().equals(t)).findFirst().orElseThrow();
                Random r = new Random(SEED * 13 + t.hashCode());
                double iv0 = s.vol() * Math.sqrt(252) * 1.1;
                for (int k = 3; k >= 1; k--) {
                    LocalDate asof = days.get(days.size() - k);
                    double spot;
                    try (PreparedStatement q = c.prepareStatement("SELECT close FROM prices WHERE ticker = ? AND \"asof\" = ?")) {
                        q.setString(1, t);
                        q.setDate(2, java.sql.Date.valueOf(asof));
                        try (ResultSet rs = q.executeQuery()) { rs.next(); spot = rs.getDouble(1); }
                    }
                    double step = spot > 300 ? 10 : 5;
                    List<LocalDate> exps = new ArrayList<>();
                    LocalDate m = asof.withDayOfMonth(1);
                    while (exps.size() < 4) {
                        LocalDate f = m.with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.FRIDAY));
                        if (f.isAfter(asof)) exps.add(f);
                        m = m.plusMonths(1);
                    }
                    for (LocalDate exp : exps) {
                        double tyr = ChronoUnit.DAYS.between(asof, exp) / 365.0;
                        double base = Math.round(spot / step) * step;
                        for (int j = -8; j <= 8; j++) {
                            double strike = base + j * step;
                            if (strike <= 0) continue;
                            for (char type : new char[]{'c', 'p'}) {
                                double iv = iv0 * (1 + 0.15 * Math.abs(strike / spot - 1) * 4) * (1 + r.nextGaussian() * 0.01);
                                double[] g = Legacy.bs(spot, strike, tyr, iv, type);
                                double mark = Math.max(0.01, Math.round(g[0] * 100) / 100.0);
                                double half = Math.max(0.01, Math.round(mark * 0.02 * 100) / 100.0);
                                String cid = t + exp.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"))
                                        + Character.toUpperCase(type) + String.format("%08d", (long) Math.round(strike * 1000));
                                int i = 1;
                                ps.setString(i++, cid); ps.setString(i++, t); ps.setString(i++, String.valueOf(Character.toUpperCase(type)));
                                ps.setDate(i++, java.sql.Date.valueOf(asof)); ps.setDate(i++, java.sql.Date.valueOf(exp));
                                ps.setDouble(i++, strike); ps.setDouble(i++, mark); ps.setDouble(i++, mark);
                                ps.setDouble(i++, Math.max(0, mark - half)); ps.setInt(i++, 1 + r.nextInt(50));
                                ps.setDouble(i++, mark + half); ps.setInt(i++, 1 + r.nextInt(50));
                                ps.setLong(i++, r.nextInt(5000)); ps.setLong(i++, r.nextInt(20000));
                                ps.setDouble(i++, Math.round(iv * 1e4) / 1e4); ps.setDouble(i++, Math.round(g[1] * 1e4) / 1e4);
                                ps.setDouble(i++, Math.round(g[5] * 1e4) / 1e4); ps.setDouble(i++, Math.round(g[3] / 365.0 * 1e4) / 1e4);
                                ps.setDouble(i++, Math.round(g[2] / 100.0 * 1e4) / 1e4); ps.setDouble(i++, Math.round(g[4] / 100.0 * 1e4) / 1e4);
                                ps.setDouble(i++, spot);
                                ps.addBatch();
                            }
                        }
                    }
                }
            }
            ps.executeBatch();
        }
    }

    private void insertFinancials(LocalDate end) throws SQLException {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try (Connection c = Database.current().connect(); PreparedStatement ps = c.prepareStatement("INSERT INTO financials VALUES (?,?,?,?,?,?,?)")) {
            for (Spec s : SPECS) {
                if (s.etf()) continue;
                Random r = new Random(SEED * 17 + s.ticker().hashCode());
                double rev = s.shares() * s.startPx() / 3.0;
                double growth = 0.03 + r.nextDouble() * 0.12;
                // annual: last 5 fiscal years, newest first; quarterly: last 8 quarters
                for (int y = 0; y < 5; y++) {
                    LocalDate fd = LocalDate.of(end.getYear() - 1 - y, 12, 31);
                    double rv = rev * Math.pow(1 + growth, 10 - y) * (1 + r.nextGaussian() * 0.03);
                    addFin(ps, s.ticker(), fd, "annual", rv, r, now);
                }
                LocalDate q = end.withDayOfMonth(1).minusMonths(end.getMonthValue() % 3 + 1).with(TemporalAdjusters.lastDayOfMonth());
                for (int k = 0; k < 8; k++) {
                    LocalDate fd = q.minusMonths(3L * k).with(TemporalAdjusters.lastDayOfMonth());
                    double rv = rev * Math.pow(1 + growth, 10 - k / 4.0) / 4 * (1 + r.nextGaussian() * 0.04);
                    addFin(ps, s.ticker(), fd, "quarterly", rv, r, now);
                }
            }
            ps.executeBatch();
        }
    }

    private static void addFin(PreparedStatement ps, String t, LocalDate fd, String period, double rev, Random r, Timestamp now) throws SQLException {
        double gp = rev * (0.35 + r.nextDouble() * 0.2), ni = rev * (0.08 + r.nextDouble() * 0.15), ebitda = ni * 1.6, opi = ni * 1.3;
        double assets = rev * 2.2, liab = assets * (0.4 + r.nextDouble() * 0.3), cash = rev * 0.25;
        Object[][] rows = {{"income", "totalRevenue", rev}, {"income", "grossProfit", gp}, {"income", "netIncome", ni},
                {"income", "ebitda", ebitda}, {"income", "operatingIncome", opi}, {"balance", "totalAssets", assets},
                {"balance", "totalLiabilities", liab}, {"balance", "totalShareholderEquity", assets - liab},
                {"balance", "cashAndShortTermInvestments", cash}};
        for (Object[] row : rows) {
            ps.setString(1, t); ps.setString(2, (String) row[0]); ps.setDate(3, java.sql.Date.valueOf(fd)); ps.setString(4, period);
            ps.setString(5, (String) row[1]); ps.setDouble(6, Math.round((Double) row[2])); ps.setTimestamp(7, now);
            ps.addBatch();
        }
    }

    private void insertUserData(LocalDate end) throws SQLException {
        new AuthService().addUser("demo", "demo");
        try (Connection c = Database.current().connect()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO saved_queries VALUES (?, ?)")) {
                String[][] q = {{"Large caps", "mktcap > 100000000000"}, {"Tech", "sect == 'TECH'"}, {"Dips", "momdip < -1"},
                        {"Low vol", "optvol < 20"}, {"Range lows", "rangescore < 20 and mktcap > 0"}};
                for (String[] x : q) { ps.setString(1, x[0]); ps.setString(2, x[1]); ps.addBatch(); }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dynamic_reports VALUES (?, ?, ?)")) {
                String[][] q = {{"tsstock", "Stock price trend", "ticker=SPY#month=12"}, {"tsdrawdown", "Drawdown", "ticker=QQQ#month=24"},
                        {"tsgrowth", "Growth comparison", "ticker=AAPL#month=36"}, {"macd", "MACD", "ticker=MSFT"},
                        {"bband", "Bollinger bands", "ticker=NVDA"}};
                for (String[] x : q) { ps.setString(1, x[0]); ps.setString(2, x[1]); ps.setString(3, x[2]); ps.addBatch(); }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO predictions SELECT ticker, \"asof\", ?, close, close, close, close, ? FROM prices WHERE ticker = ? AND \"asof\" = ?")) {
                Object[][] p = {{"AAPL", 1.10, "demo: breakout"}, {"MSFT", 1.05, "demo: steady"}, {"XOM", 0.95, "demo: mean reversion"}};
                for (Object[] x : p) {
                    // target = factor x close on the prediction date (30 trading days before the end)
                    LocalDate d = lastWeekday(end.minusDays(42));
                    ps.setDouble(1, 0); ps.setString(2, (String) x[2]); ps.setString(3, (String) x[0]); ps.setDate(4, java.sql.Date.valueOf(d));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (Statement st = c.createStatement()) {
                st.execute("UPDATE predictions SET tgtpx = round(close * CASE ticker WHEN 'AAPL' THEN 1.10 WHEN 'MSFT' THEN 1.05 ELSE 0.95 END, 2)");
            }
        }
    }
}
