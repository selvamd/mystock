package com.mystock.legacy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/** ADAPTER: DuckDB `stock_vol` (legacy stockvol.db:stockvol, same columns and units). */
public class StockVolDB {

	private Connection m_conn;
	private PreparedStatement m_pstmt;
	private int m_write_count = -1;
	private int m_total_write_count = -1;

    private Connection connectDB() throws Exception {
        return com.mystock.db.Database.current().connect();
    }

	public StockVolDB() { }

    public void create() throws Exception { /* schema.sql */ }

	public synchronized Map<String,double[]> readall() throws Exception
	{
		Map<String,double[]> res = new LinkedHashMap<String,double[]>();
        String sql = "select * from stock_vol order by lastupdate";
		try (Connection conn = connectDB(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
	        while (rs.next()) {
				double[] result = new double[5];
				result[0] = rs.getDouble("next_earnings_date");
				result[1] = rs.getDouble("pe_ratio");
				result[2] = rs.getDouble("mktcap");
				result[3] = rs.getDouble("impliedvol");
				result[4] = rs.getDouble("lastupdate");
				res.put(rs.getString("ticker"),result);
			}
		}
		return res;
	}

	public synchronized double[] read(String ticker) throws Exception
	{
		double[] result = new double[5];
		try (Connection conn = connectDB(); PreparedStatement ps = conn.prepareStatement("select * from stock_vol where ticker = ?")) {
			ps.setString(1, ticker);
			try (ResultSet rs = ps.executeQuery()) {
		        if (rs.next()) {
					result[0] = rs.getDouble("next_earnings_date");
					result[1] = rs.getDouble("pe_ratio");
					result[2] = rs.getDouble("mktcap");
					result[3] = rs.getDouble("impliedvol");
					result[4] = rs.getDouble("lastupdate");
				}
			}
		}
		return result;
	}

	public synchronized void open() throws Exception
	{
		m_total_write_count = m_write_count = 0;
		m_conn = connectDB();
        m_conn.setAutoCommit(false);
        m_pstmt = m_conn.prepareStatement("INSERT or replace INTO stock_vol VALUES(?,?,?,?,?,?)");
	}

	public synchronized void close() throws Exception
	{
		m_total_write_count += m_write_count;
        m_pstmt.executeBatch();
        m_conn.commit();
        m_pstmt.close();
		m_conn.close();
		m_total_write_count = m_write_count = -1;
	}

    public synchronized void upsert(String ticker, double earn, double pe, double mktcap, double iv) throws Exception
    {
        m_pstmt.setString(1,ticker);
		m_pstmt.setDouble(2,earn);
		m_pstmt.setDouble(3,pe);
		m_pstmt.setDouble(4,mktcap);
		m_pstmt.setDouble(5,iv);
		m_pstmt.setLong(6,System.currentTimeMillis());
		m_pstmt.addBatch();
		m_write_count++;
		m_pstmt.executeBatch();
		m_total_write_count += m_write_count;
		m_write_count = 0;
    }
}
