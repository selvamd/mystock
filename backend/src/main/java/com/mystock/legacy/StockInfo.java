package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;
import com.google.gson.stream.JsonReader;
import java.math.*;
import java.text.DecimalFormat;

//Captures base state of a stock and applies all derivations
public class StockInfo
{
	String stk;
	TimeSeries dr;
	TimeSeries ts;
	double lastpx; //last known px
	String[] dims;

	//Encapsulates calc for a single metric
	public static abstract class Metrics
	{
		protected int fromdt;
		protected int todt;
		protected StockInfo info;
		protected double dscore = 0;

		public Metrics() { this(Dates.dates[0],Dates.dates[Dates.dates.length-1]); }
		public Metrics(int from, int to) { fromdt = from; todt   = to; }
		public Metrics(int to, double score) { fromdt = 0; todt   = to; dscore = score; }

		public double compute(StockInfo in) {
			if (dscore > 0) {
				in.ts.setWindow(todt, dscore);
			} else {
				in.ts.setWindow(fromdt, todt);
			}
			Context.spydr.setWindow(in.ts);
			Context.spyts.setWindow(in.ts);
			in.dr.setWindow(in.ts);
			info = in;
			return calc();
		}

		public abstract double calc();
	}

	public StockInfo(String sym) throws Exception {
		stk = sym;
		//init();
		load(sym);
	}

	public String gets(String fld) {
		if (dims == null) throw new RuntimeException("Not initialized");
		for (int i=0;i<dimkeysarr.length;i++)
			if (fld.equals(dimkeysarr[i]))
				return dims[i];
		return Const.df.format(getd(fld));
	}

	public double getd(String fld) {
		if (metricz == null) throw new RuntimeException("Not initialized");
		StockInfo.Metrics m = metricz.get(fld);
		double d = (m == null)? 0:m.compute(this);
		if (Double.isNaN(d)) {
			throw new RuntimeException("Compute failed for " + fld + " ticker = " + stk);
		}
		return d;
	}

	public String toString() {
		String res = "";
		for (String f:dims) res += "," + f.trim();
		res = res.substring(1);
		for (String f:metricz.keySet()) 
			res += "," + Const.df.format(getd(f));
		res += "," + Const.summ(ts);
		res += "," + Const.df.format(Context.impliedvol(stk));
		double[] d = Context.symbolsvol.get(stk);
		res += "," + ((d == null || d[0] == 0)? 365:(Dates.dateToDays((int)d[0])));
		return res;
	}

	private void load(String stk) throws Exception
	{
	    int sym = Context.symbols.get(stk);
 	    TreeMap<Integer,Double> dt2px = (TreeMap<Integer,Double>) Context.pdb.load(sym,new TreeMap<Integer,Double>());
	    if (dt2px.size()==0) return;
	    lastpx = dt2px.get(dt2px.lastKey())/100.0;
	    dr = TimeSeries.buildDR(dt2px);
	    ts = TimeSeries.buildStdMktPx(dt2px);

		// checks for correctness
		if (ts.min() == ts.max())
			throw new Exception ("Price TimeSeries not loaded");

		dims = new String[dimkeysarr.length];
		String[] s = Context.symbols2.get(stk).split(",");
		for (int i=0;i<dims.length;i++) dims[i] = s[i];
	}

	static final String dimkeys      = Const.dimkeys;
	static final String[] dimkeysarr = Const.dimkeysarr;
	static Map<String,StockInfo.Metrics> metricz;

	public static String header() {
		String res = "";
		for (String f:dimkeysarr) res += "," + f;
		res = res.substring(1);
		for (String f:metricz.keySet()) res += "," + f;
		res += ",summ,impvol,earnings";
		return res;
	}

	public static void init() throws Exception
	{
	   metricz = new LinkedHashMap<String,StockInfo.Metrics>();
       int lastdt = Context.lastdt;
	   int mindt = Const.MIN_DT;
	   int rebounddt = Const.REBOUND_DT;
	   int qoqdt = Dates.offset(lastdt,-90);

	   metricz.put("symid", new StockInfo.Metrics() { public double calc() { return Context.symbols.get(info.stk); } });

	   metricz.put("mktcap", 	new StockInfo.Metrics() { public double calc() { return Double.parseDouble(Context.symbols2.get(info.stk).split(",")[dimkeysarr.length+0]); } });
	   metricz.put("pe", 	 	new StockInfo.Metrics() { public double calc() { return Double.parseDouble(Context.symbols2.get(info.stk).split(",")[dimkeysarr.length+1]); } });
	   metricz.put("eps", 	 	new StockInfo.Metrics() { public double calc() { return Double.parseDouble(Context.symbols2.get(info.stk).split(",")[dimkeysarr.length+2]); } });
	   metricz.put("dividend",  new StockInfo.Metrics() { public double calc() { return Double.parseDouble(Context.symbols2.get(info.stk).split(",")[dimkeysarr.length+3]); } });
	   metricz.put("shares", 	new StockInfo.Metrics() { public double calc() { return Double.parseDouble(Context.symbols2.get(info.stk).split(",")[dimkeysarr.length+4]); } });

	   metricz.put("ttmearnings", new StockInfo.Metrics() { public double calc() { return Double.parseDouble(Context.symbols2.get(info.stk).split(",")[dimkeysarr.length+5]); } });
	   metricz.put("ttmbookvalue", new StockInfo.Metrics() { public double calc() { return Double.parseDouble(Context.symbols2.get(info.stk).split(",")[dimkeysarr.length+6]); } });
	   metricz.put("ttmrevenue", new StockInfo.Metrics() { public double calc() { return Double.parseDouble(Context.symbols2.get(info.stk).split(",")[dimkeysarr.length+7]); } });
	   metricz.put("ttmgrossprofit", new StockInfo.Metrics() { public double calc() { return Double.parseDouble(Context.symbols2.get(info.stk).split(",")[dimkeysarr.length+8]); } });

	   metricz.put("daypnl",  new StockInfo.Metrics() { public double calc() { return info.ts.lookback(0)-info.ts.lookback(1); } });
	   metricz.put("weekpnl", new StockInfo.Metrics() { public double calc() { return info.ts.lookback(0)-info.ts.lookback(7); } });
	   metricz.put("mompnl",  new StockInfo.Metrics() { public double calc() { return info.ts.lookback(0)-info.ts.lookback(31); } });
	   metricz.put("qoqpnl",  new StockInfo.Metrics() { public double calc() { return info.ts.lookback(0)-info.ts.lookback(91); } });
	   metricz.put("yoypnl",  new StockInfo.Metrics() { public double calc() { return info.ts.lookback(0)-info.ts.lookback(366); } });
	   metricz.put("pnldir",  new StockInfo.Metrics() { public double calc() { return info.dr.ema(todt,10) - 100.0; } });

	   int yoydt = Dates.offset(lastdt,-365);
	   metricz.put("cagr", 	   new StockInfo.Metrics(yoydt, lastdt) { public double calc() { return info.ts.cagr(); } });
	   metricz.put("corr",     new StockInfo.Metrics(yoydt, lastdt) { public double calc() { return info.dr.corr(Context.spydr); } });
	   metricz.put("upcorr",   new StockInfo.Metrics(yoydt, lastdt) { public double calc() { return info.dr.dcorr(Context.spydr,Boolean.TRUE); } });
	   metricz.put("downcorr", new StockInfo.Metrics(yoydt, lastdt) { public double calc() { return info.dr.dcorr(Context.spydr,Boolean.FALSE); } });
	   metricz.put("coskew",   new StockInfo.Metrics(Dates.offset(lastdt,-60), lastdt) { public double calc() { return info.dr.coskew(Context.spydr); } });
	   metricz.put("range",      new StockInfo.Metrics(yoydt, lastdt) { public double calc() { return info.ts.max() - info.ts.min(); } });
	   metricz.put("rangescore", new StockInfo.Metrics(yoydt, lastdt) { public double calc() { return info.ts.rangescore(); } });
	   metricz.put("lastdevz",   new StockInfo.Metrics(yoydt, lastdt) { public double calc() { return info.ts.lastdevz(); } });

	   metricz.put("seasonality", new StockInfo.Metrics() { public double calc() { return info.ts.seasonality(); } });
	   metricz.put("seasonindex",   new StockInfo.Metrics() { public double calc() { return info.ts.seasonindex((todt/100)%100); } });
	   metricz.put("xadv", new StockInfo.Metrics() {
		   public double calc() {
	   			double adv = 1.0;
				int sym = Context.symbols.get(info.stk);
	   			if (Context.adv5.containsKey(sym) && Context.adv100.containsKey(sym))
	   				if (Context.adv100.get(sym) > 0 && Context.adv5.get(sym) > 0)
	   					adv = Context.adv5.get(sym)/Context.adv100.get(sym);
				return adv;
		   }
	   });

	   metricz.put("avgdr", new StockInfo.Metrics() {
			public double calc() {
				double res = 0;
				int cnt = 0;
				for (int i = 0; i < 90; i++) {
					double dr = info.ts.lookback(i)-info.ts.lookback(i+1);
					if (dr == 0) continue;
					if (++cnt > 30) break;
					res += Math.abs(dr * 100.0 / info.ts.lookback(i+1))/30.0; 
				}
				return res;
			}
	   });

	
	   metricz.put("momtr", new StockInfo.Metrics(Dates.offset(lastdt,-30), lastdt) { public double calc() { return info.ts.rangescore(); } });
	   metricz.put("px2bbpct", new StockInfo.Metrics(Dates.offset(lastdt,-30),lastdt)
	   {
		   public double calc() {
			   double avg = info.ts.mean();
			   double sd  = info.ts.sd();
			   return (100 - avg + 2 * sd)*100/(4.0*sd);
		   }
	   });

	   metricz.put("volratio", new StockInfo.Metrics() {
		   public double calc() {
	   			double ratio = 1.0;
				int sym = Context.symbols.get(info.stk);
	   			if (Context.volratio.containsKey(sym))
					ratio = Context.volratio.get(sym);
				return ratio;
		   }
	   });

	   metricz.put("signal", new StockInfo.Metrics(rebounddt, lastdt) {
		   public double calc() {
			   double ema21 = info.ts.sma(todt,21);
			   double ema60 = info.ts.sma(todt,60);
			   double range = info.ts.max() - info.ts.min();
			   //info.ts.setWindow(Dates.dates[Dates.dates.length-11],Dates.dates[Dates.dates.length-1]);
			   double r = info.ts.rangescore();
			   if (r < 20 && ema21 > ema60 && range > 10)
			   		return +1;
			   else if (r > 80 && ema21 < ema60 && range > 10)
			   		return -1;
		       return 0;
		   }
	   });

	   metricz.put("aroonup",  new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.aroonup(); } });
	   metricz.put("aroondwn", new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.aroondwn(); } });
	   metricz.put("hangtime", new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.hangtime(); } });

	   metricz.put("peak30",      new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.maxduring(30); } });
	   metricz.put("drop30",      new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.minduring(30); } });

	   metricz.put("qpeak",      new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.max() - info.ts.maxuntil(90); } });
	   metricz.put("prscore",    new StockInfo.Metrics(lastdt, Const.PR_SCORE) { public double calc() { return info.ts.rangescore(); } });
	   metricz.put("qdrop",      new StockInfo.Metrics(lastdt-10000, lastdt) { public double calc() { return info.ts.discount(); } });
	   metricz.put("qrebound",   new StockInfo.Metrics(rebounddt, lastdt) { public double calc() { return info.ts.rebound(); } });
	   metricz.put("precovpx",   new StockInfo.Metrics(20180220, 20200220) { public double calc() { return info.ts.lastfew(); } });
	   metricz.put("decovpx",    new StockInfo.Metrics(20180220, 20200220) { public double calc() { return info.ts.yidx(Dates.dates.length-1); } });

	   metricz.put("kurt",       new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.kurtosis(); } });
	   metricz.put("trisktouch",   new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.tailrisktouch(Context.impliedvol(info.stk)); } });
	   metricz.put("triskbreach",  new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.tailriskbreach(Context.impliedvol(info.stk)); } });

	   metricz.put("triskvol", new StockInfo.Metrics(mindt, lastdt) {
		   public double calc() {
			   double vol = info.ts.optvol(0);
			   double breach = info.ts.tailriskbreach(vol);
			   while (breach > 33.0 && vol < 100) 
					breach = info.ts.tailriskbreach(++vol);
		       return vol;
		   }
	   });

	   metricz.put("qdropavg", new StockInfo.Metrics(mindt, lastdt) {
		public double calc() {
			double val = 0;
			for (int i = 0; i < 365; i++) {
				int dtidx = Dates.dates.length - 1 - i;
				info.ts.setWindow(Dates.dates[dtidx-365], Dates.dates[dtidx]);
				val += info.ts.discount()/365.0;
			}
			return val;
		}
		});


	   metricz.put("volratioyy", new StockInfo.Metrics(mindt, lastdt) {
		public double calc() {
			int dtidx = Dates.dates.length - 1;
			info.ts.setWindow(Dates.dates[dtidx-365], Dates.dates[dtidx]);
			double val0 = info.ts.sd()/Math.sqrt(365.0);
			info.ts.setWindow(Dates.dates[dtidx-22], Dates.dates[dtidx]);
			double val1 = info.ts.sd()/Math.sqrt(22.0);
			return val1/val0;
		}
		});

	   metricz.put("maxpctchg", new StockInfo.Metrics(mindt, lastdt) {
		   public double calc() {
			   info.dr.setWindow(Dates.dates[Dates.dates.length - 50], Dates.dates[Dates.dates.length - 1]);
		   	   return Math.max(info.dr.max() - 100.0, 100.0 - info.dr.min());
		   }
	   });

	   metricz.put("sharpness", new StockInfo.Metrics(mindt, lastdt) {
		   public double calc() {
			   return info.dr.sharpness();
		   }
	   });

	   metricz.put("volscore",    new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.volseries(info.ts,31).zscore(); } });
	   metricz.put("histvol",     new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.volseries(info.ts,31).sma(todt,100); } });
	   metricz.put("optvol",      new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.optvol(0); } });
	   metricz.put("optivol",     new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.optivol(0); } });
	   metricz.put("rangevol",     new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.rangevol(30); } });

	   metricz.put("rsi",        new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.rsi(); } });
	   metricz.put("weekdip",    new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.dipscore(info.ts,7); } });
	   metricz.put("momdip",     new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.dipscore(info.ts,31); } });
	   metricz.put("bumpiness",  new StockInfo.Metrics(yoydt, lastdt) { public double calc() { return info.ts.bumpiness(); } });
	   metricz.put("r2",  		 new StockInfo.Metrics(yoydt, lastdt) { public double calc() { return info.ts.r2(); } });

	   metricz.put("maxdrs",  new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.dr.max_drs_ttm(); } });
	   metricz.put("mindrs",  new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.dr.min_drs_ttm(); } });
	   metricz.put("stlpm",   new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.dr.downtickspermonth(2); } });
	   metricz.put("dtpm",    new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.dr.downtickspermonth(0); } });
	   metricz.put("ema20",   new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.sma(todt,20); } });
	   metricz.put("ema60",   new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.sma(todt,60); } });
	   metricz.put("ema200",  new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.sma(todt,200); } });
	   metricz.put("beta",    new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.dr.beta(Context.spydr); } });
	   metricz.put("alpha",   new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.ts.alpharoi(Context.spyts,info.dr.beta(Context.spydr)); } });
	   metricz.put("lastpx",  new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.lastpx; } });

	   metricz.put("mktrscore", new StockInfo.Metrics(mindt, lastdt) { public double calc() { return Context.spyts.rangescore(); } });
	   metricz.put("mktdip", 	new StockInfo.Metrics(mindt, lastdt) { public double calc() { return info.dipscore(Context.spyts,7); } });
	   metricz.put("mktsindex", new StockInfo.Metrics() { public double calc() { return Context.spyts.seasonindex((todt/100)%100); } });
	   //metricz.put("stickytrend", new StockInfo.Metrics() { public double calc() { return info.dr.stickytrend(); } });
	}

	public static double dipscore(TimeSeries ts, int days) {
		try {
			TimeSeries met = new TimeSeries(Transforms.compute(new TimeSeries[] {ts},days,Transforms.PCTCHG));
			met.setWindow(Dates.dates[Dates.dates.length - 101], Dates.dates[Dates.dates.length - 1]);
			//System.out.println(met.mean()+","+met.sd() + "," + met.lastdevz());
			return met.lastdevz();
		} catch (Exception e) {
			e.printStackTrace();
			return 0;
		}
	}

	public static TimeSeries volseries(TimeSeries ts, int days) {
		try {
			TimeSeries met = new TimeSeries(Transforms.compute(new TimeSeries[] {ts},days,Transforms.OPTVOL));
			met.setWindow(Dates.dates[Dates.dates.length - 101], Dates.dates[Dates.dates.length - 1]);
			//System.out.println(met.mean()+","+met.sd() + "," + met.lastdevz());
			return met;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public static void main(String[] args)
		throws Exception
    {
		Const.path = "./docker/data/";
		execute();
	}

    public static void execute()
		throws Exception
    {
		Context.init();
		StockInfo.init();
		// if (args.length > 0) {
		// 	for (String stk: Context.symbols.keySet()) {
		// 		if (!args[0].equals(stk)) continue;
		// 		StockInfo inf = new StockInfo(stk);
		// 		String[] header = StockInfo.header().split(",");
		// 		String[] data = inf.toString().split(",");
		// 		for (int i=0;i<header.length;i++)
		// 			System.out.println(header[i]+"\t\t\t"+data[i]);
		// 	}
		// 	return;
		// }
		StockFinDB db = new StockFinDB();
		Map<String,String> income = db.read("pnlstmt", "net_income");
		Map<String,String> revenue = db.read("pnlstmt", "total_revenue");
		BufferedWriter writer = new BufferedWriter(new FileWriter(Const.path+"out.csv"));
		writer.write(StockInfo.header() + ",income,revenue");
		writer.newLine();
		for (String stk: Context.symbols.keySet()) {
			try {
				//if (!"GOOG".equals(stk)) continue;
				System.out.println("Processing ticker " + stk);
				StockInfo inf = new StockInfo(stk);
				String astr = inf.toString();
				astr += "," + ((income.containsKey(stk))? income.get(stk):"0000") + 
					    "," + ((revenue.containsKey(stk))? revenue.get(stk):"0000");
				writer.write(astr);
				writer.newLine();
			} catch (Exception e) {
				//e.printStackTrace();
				//System.out.println(e.getMessage());
				System.err.println("Failed for ticker " + stk);
			}
		}
		writer.close();
	}

}
