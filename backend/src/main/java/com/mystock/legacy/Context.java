package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;
import com.google.gson.stream.JsonReader;
import java.text.DecimalFormat;

//Provides TimeSeries based features for ML model
public class Context
{
	public static StockDB sdb;
    public static PriceDB pdb;
	public static LoginDB ldb;
	public static TradesDB tdb;
	public static StockVolDB vdb;

	public static TimeSeries spydr;
	public static TimeSeries spyts;
	public static Map<String, double[]> symbolsvol;
	public static Map<String,Integer> symbols;
	public static Map<String,String> symbols2;
	public static int lastdt;
	public static TreeSet<Integer> tradingdays;
    public static Map<Integer,Double> adv5;
    public static Map<Integer,Double> adv100;
	public static Map<Integer,Double> volratio;
	public static Set<String> etfs;

	public static void init()
	{
   	    //if (sdb != null) return; //Already inited
		try {
	   	   sdb = new StockDB();
	   	   pdb = new PriceDB();
		   ldb = new LoginDB();
		   tdb = new TradesDB();
		   vdb = new StockVolDB();

		   symbolsvol = vdb.readall();

		   etfs = new HashSet<String>();

		   for (String e:Const.etf.split(","))
			   etfs.add(e);

		   String filter = null;
		   for (String s:etfs)
			   filter = (filter == null)? "'"+s+"'":filter + ",'" + s + "'";
		   filter = "CAST(MarketCapitalization as decimal) > 1e9 or ticker in (" + filter + ")";
	  	   symbols = sdb.load(filter);

	 	   TreeMap<Integer,Double> dt2px = new TreeMap<Integer,Double>();
	 	   pdb.load(symbols.get(Const.MKTETF),dt2px);

		   tradingdays = new TreeSet<Integer>();
		   tradingdays.addAll(dt2px.keySet());

		   lastdt = dt2px.lastKey();
	  	   Dates.init(20150101,lastdt);

	 	   spydr = TimeSeries.buildDR(dt2px);
	 	   spyts = TimeSeries.buildStdMktPx(dt2px);
		   spyts.setWindow(Const.MIN_DT,Dates.dates[Dates.dates.length-1]);
		   spydr.setWindow(Const.MIN_DT,Dates.dates[Dates.dates.length-1]);

		   adv5   = pdb.adv(Dates.dates[Dates.dates.length-3],   "max", new TreeMap<Integer,Double>());
		   adv100 = pdb.adv(Dates.dates[Dates.dates.length-100], "avg", new TreeMap<Integer,Double>());
		   volratio = pdb.volratio(lastdt, new TreeMap<Integer,Double>());

	 	   symbols2 = sdb.load(Const.dimkeys,"MarketCapitalization,PERatio,EPS,DividendYield,SharesOutstanding,EBITDA,BookValue,RevenueTTM,GrossProfitTTM");
		   symbols2.remove("NUKKW");
		   symbols.remove("NUKKW");
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void log(Object... objs)
	{
        String logs = "";
        for (Object obj : objs) {
			String result = obj.toString();
			if (obj instanceof Double) {
				double d = ((Double)obj).doubleValue();
				result = String.format("%.2f", d);
			}
			logs += ", " + result;
		}

        System.out.println(logs.substring(1));
    }

	public static double impliedvol(String stk) {
		double[] d = symbolsvol.get(stk);
		double vol = (d == null)? 0:d[3];
		return Math.min(200,Math.max(vol,0));
	}

	public static int findTradingDay(int dt, int offset) {
        dt = Dates.offset(dt,offset);
        for (int i=0;i<10;i++) {
            if (tradingdays.contains(dt)) break;
            dt = Dates.offset(dt,offset);
        }
        return dt;
    }

	public static String dim(String stk, String fld) {
		String[] csv = symbols2.get(stk).split(",");
		String keys = Const.dimkeys+",MarketCapitalization,PERatio,EPS,DividendYield,SharesOutstanding";
		int i=0;
		for (String s:keys.split(","))
			if (s.equalsIgnoreCase(fld))
				return csv[i];
			else i++;
		return "";
	}

}
