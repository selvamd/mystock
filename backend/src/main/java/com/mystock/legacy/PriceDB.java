package com.mystock.legacy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/**
 * ADAPTER: DuckDB-backed replacement for legacy PriceDB (SQLite eodmktpx.db:quotes keyed by symid, int cents,
 * int yyyymmdd). Same method signatures and the SAME UNITS the legacy math expects:
 * <ul>
 *   <li>prices come back as integer cents ({@code round(dollars*100)}). The {@code prices} table stores
 *       {@code legacyTruncatedCents/100.0}, so the round trip reproduces the legacy ints exactly;</li>
 *   <li>dates come back as yyyymmdd ints; symbols are addressed by legacy symid.</li>
 * </ul>
 * Writes ({@link #upsert}) take legacy int cents and store dollars.
 */
public class PriceDB {

    private static final String COLUMNS = "asof,adjclose,open,low,close,high,adv";
    // cents expression for a DOUBLE dollar column
    private static String c(String col) { return "CAST(round(" + col + "*100) AS BIGINT)"; }
    private static final String YMD = "CAST(strftime(\"asof\", '%Y%m%d') AS INTEGER)";

    public PriceDB() { this(false); }
    public PriceDB(boolean clone) { }

    private Connection connectDB() throws Exception {
        return com.mystock.db.Database.current().connect();
    }

    public synchronized void create() throws Exception { /* DDL lives in schema.sql */ }

    private Connection m_conn;
    private PreparedStatement m_pstmt;
    private int m_write_count = -1;
    private int m_total_write_count = -1;
    private Map<Integer, String> symid2ticker;

    public synchronized boolean canRead() { return m_write_count < 0; }

    public synchronized void open() throws Exception {
        m_total_write_count = m_write_count = 0;
        symid2ticker = symidMap();
        m_conn = connectDB();
        m_conn.setAutoCommit(false);
        m_pstmt = m_conn.prepareStatement("INSERT OR REPLACE INTO prices (ticker, \"asof\", open, high, low, close, adjclose, volume) VALUES (?,?,?,?,?,?,?,?)");
    }

    public synchronized void close() throws Exception {
        m_total_write_count += m_write_count;
        m_pstmt.executeBatch();
        m_conn.commit();
        m_pstmt.close();
        m_conn.close();
        System.out.println("Total records written : " + m_total_write_count);
        m_total_write_count = m_write_count = -1;
    }

    static Map<Integer, String> symidMap() throws Exception {
        Map<Integer, String> m = new HashMap<>();
        try (Connection c = com.mystock.db.Database.current().connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT symid, ticker FROM symbols")) {
            while (rs.next()) m.put(rs.getInt(1), rs.getString(2));
        }
        return m;
    }

    private static String ticker(Connection c, int sym) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT ticker FROM symbols WHERE symid = ?")) {
            ps.setInt(1, sym);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    static java.sql.Date sqlDate(int ymd) { return java.sql.Date.valueOf(Dates_toLocal(ymd)); }
    private static java.time.LocalDate Dates_toLocal(int dt) { return java.time.LocalDate.of(dt / 10000, (dt / 100) % 100, dt % 100); }

    // ratio of longvol to currvol based on atr49 to atr1
    public Map<Integer, Double> volratio(int lastdt, Map<Integer, Double> dt2px) throws Exception {
        dt2px.clear();
        lastdt = Dates.offset(lastdt, -49);
        // Same SQL as legacy, on integer cents. SQLite max(a,NULL)/min(a,NULL) is NULL while DuckDB
        // greatest/least ignore NULLs, hence the explicit CASE.
        String sql = "select s.symid AS ticker, ((max(high)-min(low))/(7.0*avg(case when prev is null then null else greatest(high,prev)-least(low,prev) end))) AS ratio from ( " +
                "select \"asof\", ticker, " + c("open") + " AS open, " + c("high") + " AS high, " + c("low") + " AS low, " + c("close") + " AS close, " +
                "lag(" + c("close") + ") over(partition by ticker order by \"asof\") AS prev from prices where \"asof\" >= ?) q " +
                "join symbols s on s.ticker = q.ticker where \"asof\" > ? group by s.symid";
        try (Connection conn = connectDB(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, sqlDate(lastdt));
            ps.setDate(2, sqlDate(lastdt));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) dt2px.put(rs.getInt("ticker"), rs.getDouble("ratio"));
            }
        }
        return dt2px;
    }

    public Map<Integer, Double> adv(int asof, String func, Map<Integer, Double> dt2px) throws Exception {
        dt2px.clear();
        if (!func.equals("max") && !func.equals("avg")) throw new IllegalArgumentException(func);
        String sql = "select s.symid AS ticker, " + func + "(volume) as adv from prices p join symbols s on s.ticker = p.ticker where \"asof\" > ? group by s.symid";
        try (Connection conn = connectDB(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, sqlDate(asof));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) dt2px.put(rs.getInt("ticker"), rs.getDouble("adv"));
            }
        }
        return dt2px;
    }

    public static boolean is_db_current(int dt) {
        Calendar c = Calendar.getInstance();
        int today = c.get(Calendar.YEAR) * 10000 + (1 + c.get(Calendar.MONTH)) * 100 + c.get(Calendar.DAY_OF_MONTH);
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dt >= today) return true;
        int tplus = Dates.offset(dt, 1);
        if (tplus == today && c.get(Calendar.HOUR_OF_DAY) < 15) return true;
        if (tplus == today && dow == Calendar.SATURDAY) return true;
        tplus = Dates.offset(tplus, 1);
        if (tplus == today && dow == Calendar.SUNDAY) return true;
        tplus = Dates.offset(tplus, 1);
        if (tplus == today && dow == Calendar.MONDAY && c.get(Calendar.HOUR_OF_DAY) < 15)
            return true;
        return false;
    }

    public Map<Integer, Double> load(int sym, Map<Integer, Double> dt2px) throws Exception {
        return loadField(sym, "close", Const.INIT_DT, 21991201, dt2px);
    }

    private static String dbcol(String dbfld) {
        switch (dbfld.toLowerCase()) {
            case "open": case "high": case "low": case "close": case "adjclose": return c(dbfld.toLowerCase());
            case "adv": return "volume";
            default: throw new IllegalArgumentException("unknown field " + dbfld);
        }
    }

    // fld = open|close|adv|high|low|prev
    public Map<Integer, Double> loadAsof(String fld, int dt, Map<Integer, Double> sym2px) throws Exception {
        sym2px.clear();
        if (fld.toLowerCase().equals("prev"))
            dt = Context.findTradingDay(dt, -1);
        String dbfld = (fld.equalsIgnoreCase("prev")) ? "close" : fld;
        String sql = "SELECT s.symid AS ticker, " + dbcol(dbfld) + " AS fld, " + c("close") + " AS close, " + c("adjclose") + " AS adjclose FROM prices p join symbols s on s.ticker = p.ticker where \"asof\" = ?";
        try (Connection conn = connectDB(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, sqlDate(dt));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double d = rs.getDouble("fld");
                    if (!fld.toLowerCase().equals("adv"))
                        d *= (rs.getDouble("adjclose") / rs.getDouble("close"));
                    sym2px.put(rs.getInt("ticker"), d);
                }
            }
        }
        return sym2px;
    }

    // fld = open|close|adv|high|low|prev
    public Map<Integer, Double> loadField(int sym, String fld, int startdt, int enddt, Map<Integer, Double> dt2px) throws Exception {
        dt2px.clear();
        if (fld.equalsIgnoreCase("prev")) {
            startdt = Context.findTradingDay(startdt, -1);
            enddt = Context.findTradingDay(enddt, 1);
        }
        String dbfld = (fld.equalsIgnoreCase("prev")) ? "close" : fld;
        String sql = "SELECT " + YMD + " AS \"asof\", " + dbcol(dbfld) + " AS fld, " + c("close") + " AS close, " + c("adjclose") + " AS adjclose FROM prices " +
                " where ticker = ? and \"asof\" >= ? and \"asof\" <= ? order by \"asof\"";
        try (Connection conn = connectDB()) {
            String tk = ticker(conn, sym);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tk);
                ps.setDate(2, sqlDate(Math.max(startdt, 10000101)));
                ps.setDate(3, sqlDate(Math.min(enddt, 99991231)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        double d = rs.getDouble("fld");
                        if (!fld.toLowerCase().equals("adv"))
                            d *= (rs.getDouble("adjclose") / rs.getDouble("close"));
                        int asof = rs.getInt("asof");
                        if (fld.toLowerCase().equals("prev"))
                            asof = Context.findTradingDay(asof, 1);
                        dt2px.put(asof, d);
                    }
                }
            }
        }
        return dt2px;
    }

    public List<String> toCSV(int sym) throws Exception {
        List<String> result = new LinkedList<>();
        result.add(COLUMNS);
        String sql = "select " + YMD + " AS \"asof\", " + c("adjclose") + " AS adjclose, " + c("open") + " AS open, " + c("low") + " AS low, "
                + c("close") + " AS close, " + c("high") + " AS high, volume AS adv from prices where ticker = ? order by \"asof\"";
        try (Connection conn = connectDB()) {
            String tk = ticker(conn, sym);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tk);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String data = null;
                        for (String col : COLUMNS.split(","))
                            data = (data == null) ? rs.getString(col) : data + "," + rs.getString(col);
                        result.add(data);
                    }
                }
            }
        }
        return result;
    }

    public Price ohlc(int sym) throws Exception {
        Price p = new Price("" + sym);
        String sql = "select " + YMD + " AS \"asof\", ticker, " + c("adjclose") + " AS adjclose, " + c("open") + " AS open, " + c("high") + " AS high, "
                + c("low") + " AS low, " + c("close") + " AS close, volume AS adv, lag(" + c("adjclose") + ") "
                + "over(partition by ticker order by \"asof\") AS prev from prices where ticker = ? order by \"asof\"";
        try (Connection conn = connectDB()) {
            String tk = ticker(conn, sym);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tk);
                try (ResultSet rs = ps.executeQuery()) {
                    String[] cols = new String[]{"adjclose", "open", "high", "low", "close", "prev", "adv"};
                    while (rs.next())
                        p.process(rs, cols);
                }
            }
        }
        return p;
    }

    /** Legacy signature: prices in int cents, dt yyyymmdd. Stored as cents/100.0 dollars and DATE. */
    public synchronized void upsert(int sym, int dt, int op, int hi, int lo, int cl, int adjcl, long adv) throws Exception {
        String tk = symid2ticker.get(sym);
        if (tk == null) throw new IllegalArgumentException("unknown symid " + sym);
        m_pstmt.setString(1, tk);
        m_pstmt.setDate(2, sqlDate(dt));
        m_pstmt.setDouble(3, op / 100.0);
        m_pstmt.setDouble(4, hi / 100.0);
        m_pstmt.setDouble(5, lo / 100.0);
        m_pstmt.setDouble(6, cl / 100.0);
        m_pstmt.setDouble(7, adjcl / 100.0);
        m_pstmt.setLong(8, adv);
        m_pstmt.addBatch();
        m_write_count++;
        if (m_write_count % 1000 == 0) {
            m_pstmt.executeBatch();
            m_total_write_count += m_write_count;
            m_write_count = 0;
        }
    }
}
