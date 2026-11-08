package com.mystock.legacy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * ADAPTER: legacy read-only financials.db (selenium-scraped Yahoo pnlstmt, wide format) replaced by the
 * long-format `financials` table filled from Alpha Vantage (FINANCIALS job / fundamentals.statements).
 * Mapping: tbl "pnlstmt" -> statement 'income', period 'annual'; fld net_income -> netIncome,
 * total_revenue -> totalRevenue, other names are converted snake_case -> camelCase.
 * Legacy row order "order by cast(pkey as Integer)" (pkey 0 = most recent) -> fiscal_date DESC.
 * The growth-string logic in read() is unchanged.
 */
public class StockFinDB {

    private Connection connectDB() throws Exception {
        return com.mystock.db.Database.current().connect();
    }

	static String field(String fld) {
		if (fld.equals("net_income")) return "netIncome";
		if (fld.equals("total_revenue")) return "totalRevenue";
		StringBuilder b = new StringBuilder();
		boolean up = false;
		for (char ch : fld.toCharArray()) {
			if (ch == '_') { up = true; continue; }
			b.append(up ? Character.toUpperCase(ch) : ch);
			up = false;
		}
		return b.toString();
	}

	static String statement(String tbl) { return tbl.equals("pnlstmt") ? "income" : "balance"; }

	//reads and calculates growth rate of each metric (legacy: TTM vs min); TTM approximated by latest annual
	public synchronized Map<String,double[]> cagr(String tbl, String fld) throws Exception {
		String sql = "select ticker, arg_max(value, fiscal_date) last_val, min(case when value = 0 then null else value end) min_val " +
				"from financials where statement = ? and period = 'annual' and field = ? group by ticker";
		Map<String,double[]> res = new TreeMap<String,double[]>();
		try (Connection conn = connectDB(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, statement(tbl));
			ps.setString(2, field(fld));
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next())
					res.put(rs.getString("ticker"), new double[] {rs.getDouble("min_val"), rs.getDouble("last_val")});
			}
		}
		return res;
	}

	//reads and checks if the metric (specified in fld) grew every year
	public synchronized Map<String,String> read(String tbl, String fld) throws Exception
	{
		Map<String,Double> prev = new HashMap<String,Double>();
		Map<String,String> res = new TreeMap<String,String>();

        String sql = "select ticker, value from financials where statement = ? and period = 'annual' and field = ? order by ticker, fiscal_date desc";
		try (Connection conn = connectDB(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, statement(tbl));
			ps.setString(2, field(fld));
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String ticker = rs.getString("ticker");
					Double d = rs.getDouble("value");
					if (prev.containsKey(ticker)) {
						String cat = (d.doubleValue() == 0) ? "0" : "1";
						if (prev.get(ticker).doubleValue() != d.doubleValue())
							cat = (prev.get(ticker).doubleValue() > d.doubleValue()) ? "1" : "0";
						if (res.containsKey(ticker)) cat = res.get(ticker) + cat;
						res.put(ticker, cat);
					} else
						prev.put(ticker, d);
				}
			}
		}
		return res;
	}
}
