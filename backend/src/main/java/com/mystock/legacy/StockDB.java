package com.mystock.legacy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import java.io.File;
import java.nio.file.Files;

//If etlstatus = 1 then stock fundamentals data is in sync with latest
//Reset the etlstatus=0 manually in sqllite table if data elements listed
//below needs to be refreshed
public class StockDB {

	private Map<String,Integer> symbols  = new HashMap<String,Integer>();
	private Map<String,Integer> symstats = new HashMap<String,Integer>();
	private static Map<String,Integer> columns = new LinkedHashMap<String,Integer>();
	private Connection m_conn;
	private PreparedStatement m_pstmt;
	private int m_write_count = -1;
	private int m_total_write_count = -1;

	private static Map<String,String> sectmap = new HashMap<String,String>();

	static {
		sectmap.put("BASIC MATERIALS", "MANUFACTURING");
		sectmap.put("COMMUNICATION SERVICES", "TECH");
		sectmap.put("CONSUMER CYCLICAL", "TRADE");
		sectmap.put("CONSUMER DEFENSIVE", "TRADE");
		sectmap.put("ENERGY", "ENERGY");
		sectmap.put("ENERGY & TRANSPORTATION", "ENERGY");
		sectmap.put("FINANCE","FINANCE");
		sectmap.put("FINANCIAL SERVICES","FINANCE");
		sectmap.put("HEALTHCARE", "HEALTH");
		sectmap.put("INDUSTRIALS", "MANUFACTURING");
		sectmap.put("LIFE SCIENCES", "HEALTH");
		sectmap.put("MANUFACTURING", "MANUFACTURING");
		sectmap.put("REAL ESTATE", "REIT");
		sectmap.put("REAL ESTATE & CONSTRUCTION", "REIT");
		sectmap.put("TECHNOLOGY", "TECH");
		sectmap.put("TRADE & SERVICES", "TRADE");

		columns.put("AssetType",3);
		columns.put("Name",4);
		columns.put("Description",5);
		columns.put("Exchange",6);
		columns.put("Currency",7);
		columns.put("Country",8);
		columns.put("Sector",9);
		columns.put("Industry",10);
		columns.put("Address",11);
		columns.put("FullTimeEmployees",12);
		columns.put("FiscalYearEnd",13);
		columns.put("LatestQuarter",14);
		columns.put("MarketCapitalization",15);
		columns.put("EBITDA",16);
		columns.put("PERatio",17);
		columns.put("PEGRatio",18);
		columns.put("BookValue",19);
		columns.put("DividendPerShare",20);
		columns.put("DividendYield",21);
		columns.put("EPS",22);
		columns.put("RevenuePerShareTTM",23);
		columns.put("ProfitMargin",24);
		columns.put("OperatingMarginTTM",25);
		columns.put("ReturnOnAssetsTTM",26);
		columns.put("ReturnOnEquityTTM",27);
		columns.put("RevenueTTM",28);
		columns.put("GrossProfitTTM",29);
		columns.put("DilutedEPSTTM",30);
		columns.put("QuarterlyEarningsGrowthYOY",31);
		columns.put("QuarterlyRevenueGrowthYOY",32);
		columns.put("AnalystTargetPrice",33);
		columns.put("TrailingPE",34);
		columns.put("ForwardPE",35);
		columns.put("PriceToSalesRatioTTM",36);
		columns.put("PriceToBookRatio",37);
		columns.put("EVToRevenue",38);
		columns.put("EVToEBITDA",39);
		columns.put("Beta",40);
		columns.put("d52WeekHigh",41);
		columns.put("d52WeekLow",42);
		columns.put("d50DayMovingAverage",43);
		columns.put("d200DayMovingAverage",44);
		columns.put("SharesOutstanding",45);
		columns.put("SharesFloat",46);
		columns.put("SharesShort",47);
		columns.put("SharesShortPriorMonth",48);
		columns.put("ShortRatio",49);
		columns.put("ShortPercentOutstanding",50);
		columns.put("ShortPercentFloat",51);
		columns.put("PercentInsiders",52);
		columns.put("PercentInstitutions",53);
		columns.put("ForwardAnnualDividendRate",54);
		columns.put("ForwardAnnualDividendYield",55);
		columns.put("PayoutRatio",56);
		columns.put("DividendDate",57);
		columns.put("ExDividendDate",58);
		columns.put("LastSplitFactor",59);
		columns.put("LastSplitDate",60);
	}

    // ADAPTER: DuckDB table `symbols` (same columns as legacy usstocks.db:stocks) instead of SQLite.
    private Connection connectDB() throws Exception
	{
        return com.mystock.db.Database.current().connect();
    }

    // ADAPTER: SQLite getLong() on TEXT returns the numeric prefix or 0; DuckDB throws, so emulate.
    static long sqliteLong(String v) {
        if (v == null) return 0;
        v = v.trim();
        try { return Long.parseLong(v); } catch (Exception e) { }
        try { return (long) Double.parseDouble(v); } catch (Exception e) { }
        return 0;
    }

    private int maxSymid = 0;

	private String db;

    public StockDB() {
		try {
			db = "duckdb:symbols";
			// if (clone) {
			// 	File source = new File(db);
			// 	db = "usstocks.db";
			// 	File dest = new File(db);
			// 	if (dest.exists()) dest.delete();
			// 	Files.copy(source.toPath(), dest.toPath());
	        // }
			create();
			load();
		} catch (Exception e) {
			e.printStackTrace();
		}
    }

    public void create() throws Exception
    {
		// ADAPTER: DDL lives in schema.sql
    }

	public Map<String,String> load(String flds,String numflds) throws Exception
	{
		Map<String,String> res = new TreeMap<String,String>();
		Connection conn = connectDB();
        String sql = "SELECT * FROM symbols";
        Statement stmt  = conn.createStatement();
        ResultSet rs    = stmt.executeQuery(sql);
		String[] arr = (flds == null)? null:flds.split(",");
		String[] narr = (numflds == null)? null:numflds.split(",");
        while (rs.next()) {
			String str = "";
			if (arr == null && narr == null)
				str = ","+rs.getString("ticker");
			if (arr != null)
			for (String fld:arr) {
				if (fld.equals("Sector")) {
					String key = sectmap.get(rs.getString(fld));
					String ind = rs.getString("Industry");
					if (ind != null) {
						//making reasonable guess for sector based on industry wildcard match
						ind = ind.toUpperCase();
						for (String s:"BIO,DIAG,DRUG,HEALTH,MEDI".split(","))
							if (key == null && ind.indexOf(s) >= 0) key = "HEALTH";
						for (String s:"REIT,REAL".split(","))
							if (key == null && ind.indexOf(s) >= 0) key = "REIT";
						for (String s:"ASSET,BANKS,INSURANCE".split(","))
							if (key == null && ind.indexOf(s) >= 0) key = "FINANCE";
						for (String s:"OIL,SHELL".split(","))
							if (key == null && ind.indexOf(s) >= 0) key = "ENERGY";
						for (String s:"SOFT,SEMI,COMMU,INFOR,ELECT,INSTR".split(","))
							if (key == null && ind.indexOf(s) >= 0) key = "TECH";
						for (String s:"RETAIL,SERVICES,DISTRIB,SUPPLIES,LODGING".split(","))
							if (key == null && ind.indexOf(s) >= 0) key = "TRADE";
						for (String s:"MANUF,MACHINE,AUTO,AAPLI,INDUS,METAL".split(","))
							if (key == null && ind.indexOf(s) >= 0) key = "MANUFACTURING";
					}
					str += "," + ((key==null)? "OTHER":key);
				}
				else if (fld.equals("MktSize")) {
					Long l = sqliteLong(rs.getString("MarketCapitalization")); // ADAPTER
					if (l == null ) l = new Long(0);
					String type = "N/A";
					if (l.longValue() > 1_000_000 && l.longValue() < 1_000_000_000) type = "   1B";
					else if (l.longValue() < 10_000_000_000l) type  = "  10B";
					else if (l.longValue() < 20_000_000_000l) type  = "  20B";
					else if (l.longValue() < 50_000_000_000l) type  = "  50B";
					else if (l.longValue() < 100_000_000_000l) type = " 100B";
					else if (l.longValue() < 250_000_000_000l) type = " 250B";
					else type = "9999B";
					str += "," + type;
				} else {
					String v = rs.getString(fld);
					if (v == null ) v = "";
					v = v.replaceAll("[^a-zA-Z0-9_-]", " ");
					str += "," + v;
				}
			}
			if (narr != null)
			for (String fld:narr) {
				String v = rs.getString(fld);
				if (v == null || v.trim().length() == 0 || v.toLowerCase().charAt(0) == 'n')
					v = "0";
				str += "," + v;
			}
            res.put(rs.getString("ticker"),str.substring(1));
        }
        rs.close();
        stmt.close();
		conn.close();
		return res;
	}

	public Map<String,Integer> load(String filter) throws Exception
	{
		Map<String,Integer> res = new TreeMap<String,Integer>();
		Connection conn = connectDB();
        String sql = "SELECT symid,ticker,etlstatus FROM symbols";
		// ADAPTER: SQLite CAST('None' as decimal) yields 0, DuckDB raises; TRY_CAST yields NULL (-> false).
		// The filter string is only ever built from constants in Context.init.
		if (filter != null) sql += " where " + filter.replace("CAST(", "TRY_CAST(");
        Statement stmt  = conn.createStatement();
        ResultSet rs    = stmt.executeQuery(sql);
        while (rs.next())
            res.put(rs.getString("ticker"),rs.getInt("symid"));
        rs.close();
        stmt.close();
		conn.close();
		return res;
	}

	public Map<String,Integer> load() throws Exception
	{
		if (symbols.size() > 0) return symbols;
		Connection conn = connectDB();
        String sql = "SELECT symid,ticker,etlstatus FROM symbols";
        Statement stmt  = conn.createStatement();
        ResultSet rs    = stmt.executeQuery(sql);
        while (rs.next()) {
            symbols.put(rs.getString("ticker"),rs.getInt("symid"));
            symstats.put(rs.getString("ticker"),rs.getInt("etlstatus"));
            maxSymid = Math.max(maxSymid, rs.getInt("symid"));
        }
        rs.close();
        stmt.close();
		conn.close();
		return symbols;
	}

	public synchronized void open() throws Exception
	{
		m_total_write_count = m_write_count = 0;
		m_conn = connectDB();
        m_conn.setAutoCommit(false);
		String sql = "INSERT or replace INTO symbols VALUES(?,?";
		for (int i=0;i<columns.size();i++) sql += ",?";
		sql += ",?)";
        m_pstmt = m_conn.prepareStatement(sql);
	}

	public synchronized void close() throws Exception
	{
		m_total_write_count += m_write_count;
        m_pstmt.executeBatch();
        m_conn.commit();
        m_pstmt.close();
		m_conn.close();
		System.out.println("Total records written : " + m_total_write_count);
		m_total_write_count = m_write_count = -1;
	}

	public boolean loaded(String symbol)
	{
		return symstats.containsKey(symbol) && symstats.get(symbol) == 1;
	}

    public synchronized void upsert(String ticker, Map<String,String> properties) throws Exception
    {
		// ADAPTER: legacy used 1+symbols.size(), which collides when symids have gaps (e.g. after migration)
		int symid = (symbols.containsKey(ticker))? symbols.get(ticker):(maxSymid = Math.max(maxSymid, symbols.size()) + 1);
		symbols.put(ticker,symid);
        m_pstmt.setInt(1,symid);
        m_pstmt.setString(2,ticker);

		System.out.println(ticker+"="+symid+ ","+properties.size());
		symstats.put(ticker, 1);
		if (properties != null) {
			for (String fld:columns.keySet()) {
				String val = properties.get(fld);
				if (val == null) val = properties.get(fld.substring(1));
		        m_pstmt.setString(columns.get(fld),val);
				//System.out.println("setting " + fld + "=" + val);
			}
		}
        m_pstmt.setInt(2+columns.size()+1,1);  //status == 1 indicates symbol loaded
		m_pstmt.addBatch();
		m_write_count++;
		if (m_write_count % 100 == 0)
		{
			m_pstmt.executeBatch();
			m_total_write_count += m_write_count;
			m_write_count = 0;
		}
    }

	public static void main(String[] args) throws Exception
	{
		System.out.println("args "+args[0]);
		StockDB db = new StockDB();
		db.open();
		db.upsert("AAPL", new HashMap<String,String>());
		db.close();
	}
}
