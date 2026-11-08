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
import java.nio.file.Files;
import java.io.File;

public class StockFeatureDB {

    private String db;
    private String[] COLUMNS = StockFeature.headers();

    public StockFeatureDB() { this(false); }
    public StockFeatureDB(boolean clone) {
        try {
            db = "duckdb:stock_features";
		} catch (Exception e) {
			e.printStackTrace();
		}
    }

    // ADAPTER: DuckDB `stock_features` instead of SQLite oxeodmktpx.db:stockfeatures
    private Connection connectDB() throws Exception {
        return com.mystock.db.Database.current().connect();
    }

    public synchronized void create() throws Exception { /* ADAPTER: schema.sql */ }

	private Connection m_conn;
	private PreparedStatement m_pstmt;
	private int m_write_count = -1;
	private int m_total_write_count = -1;

	//can do reads only when write count < 0
	public synchronized boolean canRead()
	{
		return m_write_count < 0;
	}

    public synchronized void open() throws Exception
	{
		m_total_write_count = m_write_count = 0;
		m_conn = connectDB();
        m_conn.setAutoCommit(false);
        // ADAPTER: explicit column list (DuckDB table), same order as StockFeature.headers()
        String sql = "INSERT or replace INTO stock_features (symbol, \"asof\"";
        for (String fld: COLUMNS) sql += "," + fld;
        sql += ") VALUES(?,?";
        for (String fld: COLUMNS) sql += ",?";
        sql += ");";
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

    private double getValue(StockFeature msg, String fld) 
    {
        // if (fld.equals("ticker")) return msg.ticker();
        // if (fld.equals("name")) return msg.name();
        // if (fld.equals("sect")) return msg.sect();
        // if (fld.equals("ind")) return msg.ind();
        // if (fld.equals("mktsize")) return msg.mktsize();
        // if (fld.equals("symid")) return msg.symid();
        if (fld.equals("mktcap")) return msg.mktcap();
        if (fld.equals("pe")) return msg.pe();
        if (fld.equals("eps")) return msg.eps();
        if (fld.equals("dividend")) return msg.dividend();
        if (fld.equals("shares")) return msg.shares();
        if (fld.equals("daypnl")) return msg.daypnl();
        if (fld.equals("weekpnl")) return msg.weekpnl();
        if (fld.equals("mompnl")) return msg.mompnl();
        if (fld.equals("qoqpnl")) return msg.qoqpnl();
        if (fld.equals("yoypnl")) return msg.yoypnl();
        if (fld.equals("roi")) return msg.roi();
        if (fld.equals("corr")) return msg.corr();
        if (fld.equals("upcorr")) return msg.upcorr();
        if (fld.equals("downcorr")) return msg.downcorr();
        if (fld.equals("coskew")) return msg.coskew();
        if (fld.equals("beta")) return msg.beta();
        if (fld.equals("alpha")) return msg.alpha();
        if (fld.equals("pnldir")) return msg.pnldir();
        if (fld.equals("aroonup")) return msg.aroonup();
        if (fld.equals("aroondwn")) return msg.aroondwn();
        if (fld.equals("hangtime")) return msg.hangtime();
        if (fld.equals("qpeak")) return msg.qpeak();
        if (fld.equals("rangescore")) return msg.rangescore();
        if (fld.equals("qdrop")) return msg.qdrop();
        if (fld.equals("range")) return msg.range();
        if (fld.equals("lastdevz")) return msg.lastdevz();
        if (fld.equals("optvol")) return msg.optvol();
        if (fld.equals("optivol")) return msg.optivol();
        if (fld.equals("kurt")) return msg.kurt();
        if (fld.equals("trisktouch")) return msg.trisktouch();
        if (fld.equals("triskbreach")) return msg.triskbreach();
        if (fld.equals("rsi")) return msg.rsi();
        if (fld.equals("weekdip")) return msg.weekdip();
        if (fld.equals("momdip")) return msg.momdip();
        if (fld.equals("bumpiness")) return msg.bumpiness();
        if (fld.equals("r2")) return msg.r2();
        if (fld.equals("histvol")) return msg.histvol();
        if (fld.equals("rangevol")) return msg.rangevol();
        if (fld.equals("stlpm")) return msg.stlpm();
        if (fld.equals("dtpm")) return msg.dtpm();
        if (fld.equals("uptic")) return msg.uptic();
        if (fld.equals("ema20")) return msg.ema20();
        if (fld.equals("ema60")) return msg.ema60();
        if (fld.equals("ema200")) return msg.ema200();
        if (fld.equals("peak30")) return msg.peak30();
        if (fld.equals("drop30")) return msg.drop30();
        if (fld.equals("seasonality")) return msg.seasonality();
        if (fld.equals("seasonindex")) return msg.seasonindex();
        if (fld.equals("px2bbpct")) return msg.px2bbpct();
        if (fld.equals("momtr")) return msg.momtr();
        if (fld.equals("cci")) return msg.cci();
        if (fld.equals("cciq")) return msg.cciq();
        if (fld.equals("qrebound")) return msg.qrebound();
        if (fld.equals("prscore")) return msg.prscore();
        if (fld.equals("maxpctchg")) return msg.maxpctchg();
        if (fld.equals("sharpness")) return msg.sharpness();
        if (fld.equals("qdropavg")) return msg.qdropavg();
        if (fld.equals("signal")) return msg.signal();
        if (fld.equals("xadv")) return msg.xadv();
        if (fld.equals("volratio")) return msg.volratio();
        if (fld.equals("triskvol")) return msg.triskvol();
        return -1;
    }

    public synchronized void upsert(String ticker, int asof, StockFeature msg) throws Exception
    {
        m_pstmt.setString(1,ticker);
        m_pstmt.setDate(2,PriceDB.sqlDate(asof)); // ADAPTER: yyyymmdd -> DATE
        for (int i=0;i<COLUMNS.length;i++)
            m_pstmt.setDouble(i+3, getValue(msg, COLUMNS[i]));
		m_pstmt.addBatch();
		m_write_count++;
		if (m_write_count % 1000 == 0) {
			m_pstmt.executeBatch();
			m_total_write_count += m_write_count;
			m_write_count = 0;
		}
    }

	public static void main(String[] args) throws Exception
	{
		StockFeatureDB db = new StockFeatureDB();
		db.create();
		db.open();
        // msg.contractID = "test";
        // msg.asof = 20250131;
		// for (int i=1;i<=100;i++)
		//     db.upsert(msg);
		db.close();
	}
}
