package com.mystock.legacy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/** ADAPTER: DuckDB `price_status` (legacy usstocks.db:stocks_status, same shape: yyyymmdd + int cents). */
public class StockStatusDB {

	private Connection m_conn;
	private PreparedStatement m_pstmt;
	private int m_write_count = -1;
	private int m_total_write_count = -1;

    private Connection connectDB() throws Exception {
        return com.mystock.db.Database.current().connect();
    }

	public StockStatusDB() { }

    public void create() throws Exception { /* schema.sql */ }

	public Map<String,String> load() throws Exception
	{
		Map<String,String> symbols = new TreeMap<String,String>();
        String sql = "SELECT ticker, lastdt, lastpx, s.symid FROM symbols s left join price_status ss on s.symid = ss.symid where etlstatus = 1";
		try (Connection conn = connectDB(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
	        while (rs.next())
			{
	        	if (rs.getString("lastdt") == null)
			        symbols.put(rs.getString("ticker"), rs.getInt("symid") + ",20141231,0");
				else
					symbols.put(rs.getString("ticker"), rs.getInt("symid") + "," + rs.getString("lastdt")+","+rs.getString("lastpx"));
	        }
		}
		return symbols;
	}

	public synchronized void open() throws Exception
	{
		m_total_write_count = m_write_count = 0;
		m_conn = connectDB();
        m_conn.setAutoCommit(false);
        m_pstmt = m_conn.prepareStatement("INSERT or replace INTO price_status VALUES(?,?,?)");
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

    public synchronized void upsert(int symid, int lastdt, int lastpx) throws Exception
    {
        m_pstmt.setInt(1,symid);
        m_pstmt.setInt(2,lastdt);
        m_pstmt.setInt(3,lastpx);
		m_pstmt.addBatch();
		m_write_count++;
		if (m_write_count % 100 == 0)
		{
			m_pstmt.executeBatch();
			m_total_write_count += m_write_count;
			m_write_count = 0;
		}
    }
}
