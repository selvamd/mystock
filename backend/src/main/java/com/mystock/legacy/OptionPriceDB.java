package com.mystock.legacy;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * ADAPTER: DuckDB `option_quotes`. Legacy stored StockOptionMsg ints (prices x100, IV/greeks x10000,
 * yyyymmdd); converted at this edge to dollars, decimals and DATE.
 */
public class OptionPriceDB {

    public OptionPriceDB() { }
    public OptionPriceDB(boolean clone) { }

    private Connection connectDB() throws Exception {
        return com.mystock.db.Database.current().connect();
    }

    public synchronized void create() throws Exception { /* schema.sql */ }

	private Connection m_conn;
	private PreparedStatement m_pstmt;
	private int m_write_count = -1;
	private int m_total_write_count = -1;

	public synchronized boolean canRead() { return m_write_count < 0; }

	public synchronized void open() throws Exception
	{
		m_total_write_count = m_write_count = 0;
		m_conn = connectDB();
        m_conn.setAutoCommit(false);
        m_pstmt = m_conn.prepareStatement("INSERT or replace INTO option_quotes VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
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

    public synchronized void upsert(StockOptionMsg msg) throws Exception
    {
        m_pstmt.setString(1,msg.contractID);
        m_pstmt.setString(2,msg.ticker);
        m_pstmt.setString(3,msg.type);
        m_pstmt.setDate(4,msg.asof > 0 ? PriceDB.sqlDate(msg.asof) : null);
        m_pstmt.setDate(5,msg.expiration > 0 ? PriceDB.sqlDate(msg.expiration) : null);
        m_pstmt.setDouble(6,msg.strike/100.0);
        m_pstmt.setDouble(7,msg.last/100.0);
        m_pstmt.setDouble(8,msg.mark/100.0);
        m_pstmt.setDouble(9,msg.bid/100.0);
        m_pstmt.setInt(10,msg.bid_size);
        m_pstmt.setDouble(11,msg.ask/100.0);
        m_pstmt.setInt(12,msg.ask_size);
        m_pstmt.setLong(13,msg.volume);
        m_pstmt.setLong(14,msg.open_interest);
        m_pstmt.setDouble(15,msg.implied_volatility/10000.0);
        m_pstmt.setDouble(16,msg.delta/10000.0);
        m_pstmt.setDouble(17,msg.gamma/10000.0);
        m_pstmt.setDouble(18,msg.theta/10000.0);
        m_pstmt.setDouble(19,msg.vega/10000.0);
        m_pstmt.setDouble(20,msg.rho/10000.0);
        m_pstmt.setDouble(21,msg.upx/100.0);
		m_pstmt.addBatch();
		m_write_count++;
		if (m_write_count % 1000 == 0) {
			m_pstmt.executeBatch();
			m_total_write_count += m_write_count;
			m_write_count = 0;
		}
    }
}
