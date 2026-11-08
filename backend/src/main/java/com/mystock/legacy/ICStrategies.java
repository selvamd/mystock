package com.mystock.legacy;

import java.io.*;

import java.io.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.LinkedList;

import java.net.*;

public class ICStrategies 
{
	public static ICBackTest select(String stgy, String stk, int dte) throws Exception 
	{
		ICBackTest m = null; 
		if ("stdic".equalsIgnoreCase(stgy))
			m = ICStrategies.getStdIC(stk, dte);
		else if ("momentum".equalsIgnoreCase(stgy))
			m = ICStrategies.getMomentumAlgo(stk, dte);
		else if ("reversion".equalsIgnoreCase(stgy))
			m = ICStrategies.getReversionAlgo(stk, dte);
		return m;
	}

	public static ICBackTest getStdIC(String stk, int dte) throws Exception
	{
		return new ICBackTest(stk,dte) {
			private double[] AutoAdjustSide(int[] trdkeys, boolean iscall)
			{
				double adjdte = 21.0 + (24.0 * dte)/DEFAULT_DTE;
				BSOption b = new BSOption(upx,upx,adjdte/365.0,riskvol,'c');
				double spx = 0, lpx = 0, nspx = 0, nlpx = 0;
				if (iscall) {
					spx = trades.get(trdkeys[2])[0];
					nspx = Math.floor(b.strike(SHORT_DELTA));
					lpx = trades.get(trdkeys[3])[0];
					nlpx = nspx + width;
				} else {
					spx = trades.get(trdkeys[1])[0];
					nspx = -1 * Math.floor(b.strike(-SHORT_DELTA));
					lpx = trades.get(trdkeys[0])[0];
					nlpx = nspx + width;
				}
				//Context.log("spread",0.2 * spread);
				if (Math.abs(spx - nspx) > Math.max(0.4 * spread,5.0)) {
					if (dte < 7) nspx = nlpx = 0;
					bookAutoAdjustTrade(spx, -DEFAULT_QTY, nspx);
					if (lpx != nlpx)
						bookAutoAdjustTrade(lpx, DEFAULT_QTY, nlpx);
					return new double[] { spx, nspx, lpx, nlpx };
				}
				return null;
			}
		
			public boolean AutoAdjust() {
				Map<Double,double[]> sizeflow = net_trades_by_price();
				int[] trdkeys = last_trade_by_ic_legs(sizeflow);
		
				// Check if call side is safe
				BSOption opt = optionrisk(trdkeys[2]);
				double cdelta = Math.abs(opt.computeGreeks()[BSOption.DELTA]);
				opt = optionrisk(trdkeys[1]);
				double pdelta = Math.abs(opt.computeGreeks()[BSOption.DELTA]);
		
				double[] puts = null, calls = null;
				if (sizeflow.get(trades.get(trdkeys[1])[0])[0] != 0)
					puts = AutoAdjustSide(trdkeys,false); //false=puts
				if (sizeflow.get(trades.get(trdkeys[2])[0])[0] != 0)
					calls = AutoAdjustSide(trdkeys,true); //true=calls
		
				if (puts != null || calls != null) {
					double[] pxs = new double[] { -1*trades.get(trdkeys[0])[0], -1*trades.get(trdkeys[1])[0],
									trades.get(trdkeys[2])[0],trades.get(trdkeys[3])[0] };
					Context.log("Roll from", pxs[0],pxs[1], pxs[2],pxs[3]);
					if (puts != null) pxs[0] = Math.abs(puts[3]);
					if (puts != null) pxs[1] = Math.abs(puts[1]);
					if (calls != null) pxs[2] = calls[1];
					if (calls != null) pxs[3] = calls[3];
					Context.log("Roll to  ", pxs[0],pxs[1], pxs[2],pxs[3]);
					Context.log("Short Deltas Old",pdelta,cdelta);
					opt = optionrisk(-1*pxs[1]);
					pdelta = Math.abs(opt.computeGreeks()[BSOption.DELTA]);
					opt = optionrisk(pxs[2]);
					cdelta = Math.abs(opt.computeGreeks()[BSOption.DELTA]);
					Context.log("Short Deltas New",pdelta,cdelta);
					return true;
				}
				else
					Context.log("Short Deltas",pdelta,cdelta);
				return false;
			}
		};
	} 

	// Stock prices will be pressured to mean revert from extreme drawdowns to its histavg.
	// Hence, the algo works as follows: 
	// Reference is set as initial spot drawdown val if inside 10% of its histavg  
	// Else assume a gap up/down and (a) start one-sided. (b) Set ref = spot + 0.5 * delta
	// Monitor and flip direction to mean revert if any one or more happens:  
	// Drawdown changes by higher or lower than 5% around ref
	// Market (SPY) weekpnl goes higher or lower than 4% band 
	public static ICBackTest getReversionAlgo(String stk, int dte) throws Exception
	{
		return new ICBackTest(stk,dte) {
			private double weekpnl = 0;
			private double refupx = 0;
			private double drift = 0;

			public void initic() {
				refupx = upx;
			}

			public String logheader() {
				return super.logheader() + ",drift,weekpnl"; 
			}

			public String log() {
				return super.log() + "," + drift + "," + weekpnl;
			}

			/*
			 * Adjustment done 
			 * On bullish breach, re-center based on new price following the trend
			 * On bearish breach, re-price put side (added safety) but close 
			 * call side (play the rebound)
			 */
			public boolean AutoAdjust() {

				Map<Double,double[]> sizeflow = net_trades_by_price();
				int[] trdkeys = last_trade_by_ic_legs(sizeflow);
				int nowdt =  Dates.offset(initdt, DEFAULT_DTE - dte);
				this.feat.set(nowdt);
				weekpnl = this.feat.ref.weekpnl();
				drift = (upx - refupx)/(refupx + trades.get(trdkeys[1])[0]); 
				Context.log("====>> Metric Date", nowdt, "drift", drift, "weekpnl", weekpnl);
				double adjdte = 21.0 + (24.0 * dte)/DEFAULT_DTE;
				BSOption b = new BSOption(upx,upx,adjdte/365.0,riskvol,'c');
				if (weekpnl > 3.0 || drift > 0.66) {
					//Context.log("Bull - Re-price 2 sided spread");
					double nspx = -1 * Math.floor(b.strike(-SHORT_DELTA));
					bookAutoAdjustTrade(trades.get(trdkeys[1])[0], -DEFAULT_QTY, nspx);
					bookAutoAdjustTrade(trades.get(trdkeys[0])[0], DEFAULT_QTY, nspx+width);
					if (trdkeys[2] > 0) {
						nspx = Math.floor(b.strike(SHORT_DELTA));
						bookAutoAdjustTrade(trades.get(trdkeys[2])[0], -DEFAULT_QTY, nspx);
						bookAutoAdjustTrade(trades.get(trdkeys[3])[0], DEFAULT_QTY, nspx + width);
					}
					refupx = upx;
					return true;
				} 
				if (weekpnl < -3.0 || drift < -0.66) {
					//Context.log("Bear - Close call spread and re-price put spread");
					double nspx = -1 * Math.floor(b.strike(-SHORT_DELTA));
					bookAutoAdjustTrade(trades.get(trdkeys[1])[0], -DEFAULT_QTY, nspx);
					bookAutoAdjustTrade(trades.get(trdkeys[0])[0], DEFAULT_QTY, nspx+width);
					if (trdkeys[2] > 0) {
						bookAutoAdjustTrade(trades.get(trdkeys[2])[0], -DEFAULT_QTY, 0);
						bookAutoAdjustTrade(trades.get(trdkeys[3])[0], DEFAULT_QTY, 0);
					}					
					refupx = upx;
					return true;
				} 
				return false;
			}
		};
	} 

	public static ICBackTest getReversionAlgoOld(String stk, int dte) throws Exception
	{
		return new ICBackTest(stk,dte) {
			private double momavgdip = 0;
			private double momdip = 0;
			public void initic() {
				for (int dt = Dates.offset(initdt, -45); dt <= initdt; dt = Dates.offset(dt,1)) {
					this.feat.set(dt);
					if (momdip == 0) momdip = this.feat.momdip();
					momavgdip += this.feat.momdip();
				}
				momavgdip /= 45.0;
				Context.log("Init momavgdip ", momavgdip);
			}

			public String logheader() {
				return super.logheader() + ",momdip,momavgdip"; 
			}

			public String log() {
				return super.log() + "," + momdip + "," + momavgdip;
			}
					
			public boolean AutoAdjust() {
				//SHORT_DELTA = 0.20;
				Map<Double,double[]> sizeflow = net_trades_by_price();
				int[] trdkeys = last_trade_by_ic_legs(sizeflow);
				double metric = feat.momdip();
				//momdip = (momdip+metric)/2.0;
				momdip = metric;
				momavgdip = (metric+29*momavgdip)/30.0;
				Context.log("====>> Metric MOMDIP", momdip,"MOMAVGDIP",momavgdip);
				if (momdip - momavgdip > -0.5 && momdip - momavgdip < 0.5)	return false;
				double adjdte = 21.0 + (24.0 * dte)/DEFAULT_DTE;
				BSOption b = new BSOption(upx,upx,adjdte/365.0,riskvol,'c');
				if (momdip < momavgdip && trdkeys[2] >= 0) {
					double nspx = -1 * Math.floor(b.strike(-SHORT_DELTA));
					if (trdkeys[1] == -1) {
						bookAutoAdjustTrade(0, -DEFAULT_QTY, nspx);
						bookAutoAdjustTrade(0, DEFAULT_QTY, nspx + width);
					}
					bookAutoAdjustTrade(trades.get(trdkeys[2])[0], -DEFAULT_QTY, 0);
					bookAutoAdjustTrade(trades.get(trdkeys[3])[0], DEFAULT_QTY, 0);
					return true;
				} 
				if (momdip > momavgdip && trdkeys[1] >= 0) {
					double nspx = Math.floor(b.strike(SHORT_DELTA));
					if (trdkeys[2] == -1) {
						bookAutoAdjustTrade(0, -DEFAULT_QTY, nspx);
						bookAutoAdjustTrade(0, DEFAULT_QTY, nspx + width);
					}
					bookAutoAdjustTrade(trades.get(trdkeys[1])[0], -DEFAULT_QTY, 0);
					bookAutoAdjustTrade(trades.get(trdkeys[0])[0], DEFAULT_QTY, 0);
					return true;
				} 
				return false;
			}
		};
	} 

	public static ICBackTest getMomentumAlgo(String stk, int dte) throws Exception
	{
		return new ICBackTest(stk,dte) {
			
			public boolean AutoAdjust() {
				//SHORT_DELTA = 0.20;
				Map<Double,double[]> sizeflow = net_trades_by_price();
				int[] trdkeys = last_trade_by_ic_legs(sizeflow);
				double metric = feat.momdip();
				Context.log("Metric MOMDIP", metric);
				if (metric > -0.5 && metric < 0.5)	return false;
				double adjdte = 21.0 + (24.0 * dte)/DEFAULT_DTE;
				BSOption b = new BSOption(upx,upx,adjdte/365.0,riskvol,'c');
				if (metric > 0 && trdkeys[2] >= 0) {
					double nspx = -1 * Math.floor(b.strike(-SHORT_DELTA));
					if (trdkeys[1] == -1) {
						bookAutoAdjustTrade(0, -DEFAULT_QTY, nspx);
						bookAutoAdjustTrade(0, DEFAULT_QTY, nspx + width);
					}
					bookAutoAdjustTrade(trades.get(trdkeys[2])[0], -DEFAULT_QTY, 0);
					bookAutoAdjustTrade(trades.get(trdkeys[3])[0], DEFAULT_QTY, 0);
					return true;
				} 
				if (metric < 0 && trdkeys[1] >= 0) {
					double nspx = Math.floor(b.strike(SHORT_DELTA));
					if (trdkeys[2] == -1) {
						bookAutoAdjustTrade(0, -DEFAULT_QTY, nspx);
						bookAutoAdjustTrade(0, DEFAULT_QTY, nspx + width);
					}
					bookAutoAdjustTrade(trades.get(trdkeys[1])[0], -DEFAULT_QTY, 0);
					bookAutoAdjustTrade(trades.get(trdkeys[0])[0], DEFAULT_QTY, 0);
					return true;
				} 
				return false;
			}
		};
	} 

}
