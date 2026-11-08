package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;
import com.google.gson.stream.JsonReader;

import java.text.DecimalFormat;

//Provides TimeSeries for a portfolio
public class StockFeature
{
	private String stk;
	private Price price;
	private int cutoffdt;
	private TimeSeries ts;
	private TimeSeries dr;
	private TimeSeries cci;
	StockFeature ref;

	public StockFeature(String sym) throws Exception {
		stk = sym;
		int isym = Context.symbols.get(stk);
		price = Context.pdb.ohlc(isym);
		set(price.lastdt());
		window(360);
	}

	public Price price() { return price; }

	public void reset() { cutoffdt = 0; ts = dr = null; }

	public void ref(StockFeature mkt) { this.ref = mkt; }

	//Build new timeseries asof any cutoff date
	public StockFeature set(int dt)
	{
		cutoffdt = dt;
		if (price.firstdt() > cutoffdt || price.lastdt() < cutoffdt)
			throw new RuntimeException("Data Insufficient for Cutoffdt " + dt);
		ts = TimeSeries.build(price.stdpx(dt));
		dr = TimeSeries.build(price.eoddr(dt));
		cci = TimeSeries.build(price.midpx(dt));
		if (ref != null && ref != this) ref.set(cutoffdt); // FIX: legacy recursed forever when ref == this (StockModel: mkt.values(mkt))
		return this;
	}

	//Set time window for a calculation
	public StockFeature window(int days)
	{
		int idt = Dates.offset(cutoffdt,-1*days);
		if (days == 0) idt = Const.INIT_DT;
		if (price.firstdt() > idt)
			throw new RuntimeException("Data Insufficient for Time window " + idt);
		ts.setWindow(idt,cutoffdt);
		dr.setWindow(idt,cutoffdt);
		cci.setWindow(idt,cutoffdt);
		if (ref != null && ref != this) ref.window(days); // FIX: see set()
		return this;
	}

	public String ticker()  { return stk; }
	public String name()    { return Context.symbols2.get(stk).split(",")[1]; }
	public String sect()    { return Context.symbols2.get(stk).split(",")[2]; }
	public String ind()     { return Context.symbols2.get(stk).split(",")[3]; }
	public String mktsize() { return Context.symbols2.get(stk).split(",")[4]; }

	public int symid()       { return Context.symbols.get(stk); }
	public double mktcap()   { return Double.parseDouble(Context.symbols2.get(stk).split(",")[Const.dimkeysarr.length+0]); }
	public double pe()       { return Double.parseDouble(Context.symbols2.get(stk).split(",")[Const.dimkeysarr.length+1]); }
	public double eps()      { return Double.parseDouble(Context.symbols2.get(stk).split(",")[Const.dimkeysarr.length+2]); }
	public double dividend() { return Double.parseDouble(Context.symbols2.get(stk).split(",")[Const.dimkeysarr.length+3]); }
	public double shares()   { return Double.parseDouble(Context.symbols2.get(stk).split(",")[Const.dimkeysarr.length+4]); }

	public double daypnl()  { window(366); return ts.lookback(0)-ts.lookback(1);   }
	public double weekpnl() { window(366); return ts.lookback(0)-ts.lookback(7);   }
	public double mompnl()  { window(366); return ts.lookback(0)-ts.lookback(31);  }
	public double qoqpnl()  { window(366); return ts.lookback(0)-ts.lookback(91);  }
	public double yoypnl()  { window(366); return ts.lookback(0)-ts.lookback(366); }
	public double roi()     { window(365); return ts.cagr(); }

	public double corr()     { window(365); return dr.corr(ref.dr); }
	public double upcorr()   { window(365); return dr.dcorr(ref.dr,Boolean.TRUE);  }
	public double downcorr() { window(365); return dr.dcorr(ref.dr,Boolean.FALSE); }
	public double coskew()   { window(60);  return dr.coskew(ref.dr);   }
	public double beta()     { window(365); return dr.beta(ref.dr);  }
	public double alpha()    { window(365); return dr.alpharoi(ref.dr,dr.beta(ref.dr)); }
	public double pnldir()    { window(365); return dr.ema(cutoffdt,10) - 100.0; }

	public double aroonup()  	{ window(365); return ts.aroonup(); }
	public double aroondwn()  	{ window(365); return ts.aroondwn(); }
	public double hangtime()  	{ window(365); return ts.hangtime(); }
	public double qpeak()  		{ window(365); return ts.max()-ts.maxuntil(90); }
	public double rangescore()  { window(365); return ts.rangescore(); }
	public double qdrop()  		{ window(365); return ts.discount(); }
	public double range()  		{ window(365); return ts.max()-ts.min(); }
	public double lastdevz()  	{ window(365); return ts.lastdevz(); }
	public double optvol()  	{ window(365); return ts.optvol(); }
	public double optivol()  	{ window(365); return ts.optivol(0); }
	public double rangevol()  	{ window(30); return ts.rangevol(30); }
	public double kurt()  		{ window(365); return ts.kurtosis(); }
	public double trisktouch()  { window(365); return ts.tailrisktouch(0); }
	public double triskbreach() { window(365); return ts.tailriskbreach(0); }
	public double rsi()  		{ window(365); return ts.rsi(); }
	public double weekdip()  	{ window(365); return dipscore(ts,7); }
	public double momdip()  	{ window(365); return dipscore(ts,31); }
	public double bumpiness()  	{ window(365); return ts.bumpiness(); }
	public double r2()  		{ window(365); return ts.r2(); }
	public double histvol()  	{ window(365); return volseries(ts,31).sma(cutoffdt,100); }
	public double stlpm()  		{ window(365); return dr.downtickspermonth(2); }
	public double dtpm()  		{ window(365); return dr.downtickspermonth(0); }
	public double uptic()  	    { window(365); return 1.0-dr.downticks(0,10)/10.0; }
	public double ema20()  		{ window(365); return ts.ema(cutoffdt,20); }
	public double ema60()  		{ window(365); return ts.ema(cutoffdt,60); }
	public double ema200()  	{ window(365); return ts.ema(cutoffdt,200); }
	public double peak30()  	{ window(365); return ts.maxduring(30); }
	public double drop30()  	{ window(365); return ts.minduring(30); }
	public double seasonality() { window(365); return ts.seasonality(); }
	public double seasonindex() { window(365); return ts.seasonindex((cutoffdt/100)%100); }
	public double px2bbpct()  	{ window(21); return (100 - ts.mean() + 2 * ts.sd())*100/(4.0*ts.sd()); }
	public double momtr()  	    { window(30); return ts.rangescore(); }
	public double cci()  	    { window(21); return  (cci.lookback(0) - cci.mean()) / (1.5 * cci.md()); }
	public double cciq()  	    { window(90); return  (cci.lookback(0) - cci.mean()) / (1.5 * cci.md()); }
	public double qrebound()  	{ window(90); return ts.rebound(); }
	public double prscore()  	{ ts.setWindow(cutoffdt,Const.PR_SCORE); return ts.rangescore(); }
	public double maxpctchg()   { window(50); return Math.max(dr.max() - 100.0, 100.0 - dr.min()); }
	public double sharpness()   { return dr.sharpness(); }

	public double qdropavg() {
		double val = 0;
		for (int i = 0; i < 365; i++) {
			int idt = Dates.offset(cutoffdt, i - 365 * 2);
			int iedt = Dates.offset(cutoffdt, i - 365);
			ts.setWindow(idt, iedt);
			val += ts.discount()/365.0;
		}
		return val;
	}


	public double signal()
	{
		double ema21 = ts.sma(cutoffdt,21);
		double ema60 = ts.sma(cutoffdt,60);
		double range = ts.max() - ts.min();
		double r = ts.rangescore();
		if (r < 20 && ema21 > ema60 && range > 10)
			 return +1;
		else if (r > 80 && ema21 < ema60 && range > 10)
			 return -1;
		return 0;
	}

	public double xadv()
	{
		TimeSeries adv = TimeSeries.build(price.stdadv(cutoffdt));
		return adv.sma(cutoffdt,5)/adv.sma(cutoffdt,100);
	}

	public double volratio()
	{
		TimeSeries atr = TimeSeries.build(price.atr(cutoffdt));
		double datr = atr.sma(cutoffdt,50) * ts.at(cutoffdt)/100.0;
		int idt = Dates.offset(cutoffdt,-50);
		ts.setWindow(idt,cutoffdt);
		return (ts.max()-ts.min())/(datr*Math.sqrt(50));
	}

	private double dipscore(TimeSeries ts, int days)
	{
		try {
			TimeSeries met = new TimeSeries(Transforms.compute(new TimeSeries[] {ts}, days, Transforms.PCTCHG));
			met.setWindow(Dates.offset(cutoffdt,-1*(100+days)), cutoffdt);
			return met.lastdevz();
		} catch (Exception e) {
			e.printStackTrace();
			return 0;
		}
	}

	public double triskvol() {
		double vol = ts.optvol(0);
		double breach = ts.tailriskbreach(vol);
		while (breach > 33.0 && vol < 300) 
			 breach = ts.tailriskbreach(++vol);
		return vol;
	}
 

	private TimeSeries volseries(TimeSeries ts, int days)
	{
		try {
			TimeSeries met = new TimeSeries(Transforms.compute(new TimeSeries[] {ts}, days, Transforms.OPTVOL));
			met.setWindow(Dates.offset(cutoffdt,-1*(100+days)), cutoffdt);
			return met;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public String header(String pre) {
		String[] head = "rangescore,prscore,range,px2bbpct,mompnl,momdip,weekpnl,weekdip,lastdevz,volratio,xadv,optvol,uptic,corr,beta".split(",");
		String res = "";
		for (String s: head) res += "," + pre + s;
		return res.substring(1);
	}

	public String values(StockFeature mkt)
	{
		ref(mkt);
		String aline = "" + rangescore();
		aline += "," + prscore();
		aline += "," + range();
		aline += "," + px2bbpct();
		aline += "," + mompnl();
		aline += "," + momdip();
		aline += "," + weekpnl();
		aline += "," + weekdip();
		aline += "," + lastdevz();
		aline += "," + volratio();
		aline += "," + xadv();
		aline += "," + optvol();
		aline += "," + uptic();
		aline += "," + corr();
		aline += "," + beta();
		return aline;
	}

	// public static void info(String[] symbols,Map<String,StockFeature> sect, StockFeature mkt) throws Exception
	// {
	// }
	public static String[] headers() {
		String headers = "mktcap,pe,eps,dividend,shares,daypnl,weekpnl,mompnl,qoqpnl,triskvol," + 
			"yoypnl,roi,corr,upcorr,downcorr,coskew,beta,alpha,pnldir,aroonup,aroondwn," + 
			"hangtime,qpeak,rangescore,qdrop,range,lastdevz,optvol,optivol,kurt,trisktouch," + 
			"triskbreach,rsi,weekdip,momdip,bumpiness,r2,histvol,stlpm,dtpm,uptic,ema20," + 
			"ema60,ema200,peak30,drop30,seasonality,seasonindex,px2bbpct,momtr,cci,cciq," + 
			"qrebound,prscore,maxpctchg,sharpness,qdropavg,signal,xadv,volratio,rangevol";
		return headers.split(",");
	}

	public static void main(String[] args) throws Exception
    {
		Const.path = "./data/";
		Context.init();
		StockFeature f = new StockFeature(args[0]);
		System.out.println("Triskvol="+f.rangevol());
		// int lastdt = Dates.lastdt();
		// for (int dt = lastdt - 10000; dt <= lastdt; dt = Dates.offset(dt,1)) {
		// 	f.set(dt);
		// 	TimeSeries px = TimeSeries.build(f.price.eodpx(dt));
		// 	System.out.println(dt+"\t"+ px.lookback(0) +"\t"+f.cci()+"\t"+f.cciq()+"\t"+f.momdip());
		// }
		//double vol = Double.parseDouble(args[1]);
		//System.out.println(f.ts.tailrisktouch(vol));
		//System.out.println(f.ts.tailriskbreach(vol));
		//System.out.println(f.optvol());
		//System.out.println(f.histvol());
		//System.out.println(f.px2bbpct()+","+f.maxpctchg());
	}

}
