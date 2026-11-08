package com.mystock.legacy;

/**
 * ADAPTER (stub): legacy trades_log access moved to com.mystock.services.TradeService (prepared statements,
 * same semantics incl. qty 0 = delete leg); kept only because Context.init constructs it.
 */
public class TradesDB {
	public TradesDB() { }
	public TradesDB(boolean clone) { }
}
