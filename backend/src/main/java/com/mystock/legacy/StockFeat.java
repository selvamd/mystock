package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;
import com.google.gson.stream.JsonReader;

import java.text.DecimalFormat;

//Provides TimeSeries for a portfolio
public class StockFeat
{
	private String stk;
	TimeSeries dr;
	TimeSeries ts;
	double lastpx;

	public static abstract class PortBuilder
	{
		private TimeSeriesData mw;
		private TimeSeriesData ew;
		protected String stk;
		protected StockFeat feat;
		private boolean eqw;

		public PortBuilder(boolean mwvalid)
		{
			if (mwvalid)
				mw = new TimeSeriesData();
			ew = new TimeSeriesData();
		}

		public abstract boolean filter();

		public TimeSeriesData mw() { return mw; }
		public TimeSeriesData ew() { return ew; }

		public void process(String s, StockFeat f)
		{
			feat = f;stk = s;
			if (feat == null || feat.ts == null) return;
			if (!filter()) return;
			double shr = Double.parseDouble(Context.dim(stk,"sharesoutstanding"));
			double weight = 0;
			TimeSeriesData temp = null;
			if (mw != null) {
				weight = shr * feat.lastpx / 100 / 1_000_000_000.00;
				temp = Transforms.compute(feat.ts.data(), weight, Transforms.MUL);
				if (!Double.isNaN(temp.last()))
					mw = Transforms.compute(mw, temp, Transforms.ADD);
			}
			temp = Transforms.compute(feat.ts.data(), 1.0, Transforms.MUL);
			if (!Double.isNaN(temp.last()))
				ew = Transforms.compute(ew, temp, Transforms.ADD);
		}
	}

	//Encapsulates calc for a single metric
	public static abstract class Metrics
	{
		protected int lookback; //lookback days (adjusted to get timeseries)
		//duration - characteric of the measure and is fixed
		protected int duration;
		protected int fromdt;
		protected int todt;
		protected StockFeat info;
		protected double dscore;

		public Metrics() { this(0); }
		public Metrics(int p) { duration = p; }
		public Metrics(double score) { dscore = score; }

		public void span(int s) { lookback = s; }

		public double compute(StockFeat in) {
			todt   = Dates.dates[Dates.dates.length - 1 - lookback];
			fromdt = Dates.dates[Dates.dates.length - 1 - lookback - duration];
			if (duration == 0) fromdt = Dates.dates[0];

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

	public StockFeat(String sym, TreeMap<Integer,Double> dt2px) throws Exception {
		stk = sym;
		if (dt2px.size()==0)
			throw new Exception ("Invalid timeseries");
	    dr = TimeSeries.buildDR(dt2px);
	    ts = TimeSeries.buildStdMktPx(dt2px);
		if (ts.min() == ts.max())
			throw new Exception ("Price TimeSeries not loaded");
	}

	public StockFeat(String sym) throws Exception {
		stk = sym;
		load(sym);
	}

	public String gets(String fld, int lookbackdays) {
		return Const.df.format(getd(fld,lookbackdays));
	}

	public double getd(String fld, int lookbackdays) {
		if (metricz == null) throw new RuntimeException("Not initialized");
		StockFeat.Metrics m = metricz.get(fld);
		if (m == null) return 0;
		m.span(lookbackdays);
		double d = m.compute(this);
		if (Double.isNaN(d)) {
			System.out.println("Compute failed "+stk+","+fld);
			//throw new RuntimeException("Compute failed");
		}
		return d;
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
	}

	public static String header() {
		String res = "name";
		for (String m:metricz.keySet())
			res += "," + m;
		return res;
	}

	public String toString() {
		String res = this.stk;
		for (String m:metricz.keySet())
			res += "," + gets(m,0);
		return res;
	}

	//static final String dimkeys      = "ticker,Name,Sector,Industry,MktSize";
	//static final String[] dimkeysarr = "ticker,name,sect,ind,mktsize".split(",");
	static Map<String,StockFeat.Metrics> metricz;
	public static void init() throws Exception
	{
	   metricz = new LinkedHashMap<String,StockFeat.Metrics>();
       int lastdt = Context.lastdt;
	   //metricz.put("mktcap",  new StockFeat.Metrics() { public double calc() { return Double.parseDouble(Context.symbols2.get(info.stk).split(",")[dimkeysarr.length+0]); } });
	   //metricz.put("pe", 	  new StockFeat.Metrics() { public double calc() { return Double.parseDouble(Context.symbols2.get(info.stk).split(",")[dimkeysarr.length+1]); } });
	   metricz.put("daypnl",  new StockFeat.Metrics() { public double calc() {
		   // info.ts.print();
		   // System.out.println(info.ts.lookback(0)+","+info.ts.lookback(1));
		   return info.ts.lookback(0)-info.ts.lookback(1); } });
	   metricz.put("weekpnl", new StockFeat.Metrics() { public double calc() { return info.ts.lookback(0)-info.ts.lookback(7); } });
	   metricz.put("mompnl",  new StockFeat.Metrics() { public double calc() { return info.ts.lookback(0)-info.ts.lookback(31); } });
	   metricz.put("qoqpnl",  new StockFeat.Metrics() { public double calc() { return info.ts.lookback(0)-info.ts.lookback(91); } });
	   metricz.put("yoypnl",  new StockFeat.Metrics() { public double calc() { return info.ts.lookback(0)-info.ts.lookback(366); } });

	   metricz.put("roi", 	   new StockFeat.Metrics(365) { public double calc() { return info.ts.cagr(); } });
	   metricz.put("corr",     new StockFeat.Metrics(365) { public double calc() { return info.dr.corr(Context.spydr); } });
	   metricz.put("upcorr",   new StockFeat.Metrics(365) { public double calc() { return info.dr.dcorr(Context.spydr,Boolean.TRUE); } });
	   metricz.put("downcorr", new StockFeat.Metrics(365) { public double calc() { return info.dr.dcorr(Context.spydr,Boolean.FALSE); } });
	   metricz.put("coskew",   new StockFeat.Metrics(60) { public double calc() { return info.dr.coskew(Context.spydr); } });

	   metricz.put("seasonality", 	new StockFeat.Metrics() { public double calc() { return info.ts.seasonality(); } });
	   metricz.put("seasonindex", 	new StockFeat.Metrics() { public double calc() { return info.ts.seasonindex((todt/100)%100); } });
	   metricz.put("aroonup",  	  	new StockFeat.Metrics(365) { public double calc() { return info.ts.aroonup(); } });
	   metricz.put("aroondwn", 	  	new StockFeat.Metrics(365) { public double calc() { return info.ts.aroondwn(); } });
	   metricz.put("hangtime", 	  	new StockFeat.Metrics(365) { public double calc() { return info.ts.hangtime(); } });

	   metricz.put("peak30",      new StockFeat.Metrics() { public double calc() { return info.ts.maxduring(30); } });
	   metricz.put("drop30",      new StockFeat.Metrics() { public double calc() { return info.ts.minduring(30); } });

	   metricz.put("px2bbpct", new StockFeat.Metrics(21)
	   {
		   public double calc() {
			   double avg = info.ts.mean();
			   double sd  = info.ts.sd();
			   return (100 - avg + 2 * sd)*100/(4.0*sd);
		   }
	   });

	   metricz.put("qpeak",      new StockFeat.Metrics(365) { public double calc() { return info.ts.max() - info.ts.maxuntil(90); } });
	   metricz.put("momtr", 	 new StockFeat.Metrics(30) { public double calc() { return info.ts.rangescore(); } });
	   metricz.put("rangescore", new StockFeat.Metrics(365) { public double calc() { return info.ts.rangescore(); } });
	   metricz.put("prscore",    new StockFeat.Metrics(Const.PR_SCORE) { public double calc() { return info.ts.rangescore(); } });
	   metricz.put("qdrop",      new StockFeat.Metrics(365) { public double calc() { return info.ts.discount(); } });
	   metricz.put("qrebound",   new StockFeat.Metrics(90) { public double calc() { return info.ts.rebound(); } });
	   metricz.put("range",      new StockFeat.Metrics(365) { public double calc() { return info.ts.max() - info.ts.min(); } });
	   metricz.put("lastdevz",   new StockFeat.Metrics(365) { public double calc() { return info.ts.lastdevz(); } });
	   metricz.put("optvol",     new StockFeat.Metrics(365) { public double calc() { return info.ts.optvol(); } });
	   metricz.put("optivol",     new StockFeat.Metrics(365) { public double calc() { return info.ts.optivol(0); } });
	   metricz.put("kurt",       new StockFeat.Metrics(365) { public double calc() { return info.ts.kurtosis(); } });

	   metricz.put("trisktouch",   new StockFeat.Metrics(365) { public double calc() { return info.ts.tailrisktouch(0); } });
	   metricz.put("triskbreach",  new StockFeat.Metrics(365) { public double calc() { return info.ts.tailriskbreach(0); } });

	   metricz.put("rsi",        new StockFeat.Metrics(365) { public double calc() { return info.ts.rsi(); } });
	   metricz.put("weekdip",    new StockFeat.Metrics(365) { public double calc() { return info.dipscore(info.ts,todt,7); } });
	   metricz.put("momdip",   	 new StockFeat.Metrics(365) { public double calc() { return info.dipscore(info.ts,todt,31); } });
	   metricz.put("bumpiness",  new StockFeat.Metrics(365) { public double calc() { return info.ts.bumpiness(); } });
	   metricz.put("r2",         new StockFeat.Metrics(365) { public double calc() { return info.ts.r2(); } });
	   metricz.put("histvol",    new StockFeat.Metrics(365) { public double calc() { return info.volseries(info.ts,31).sma(todt,100); } });

	   metricz.put("stlpm",  new StockFeat.Metrics(365) { public double calc() { return info.dr.downtickspermonth(2); } });
	   metricz.put("dtpm",   new StockFeat.Metrics(365) { public double calc() { return info.dr.downtickspermonth(0); } });
	   metricz.put("ema20",  new StockFeat.Metrics(365) { public double calc() { return info.ts.ema(todt,20); } });
	   metricz.put("ema60",  new StockFeat.Metrics(365) { public double calc() { return info.ts.ema(todt,60); } });
	   metricz.put("ema200", new StockFeat.Metrics(365) { public double calc() { return info.ts.ema(todt,200); } });
	   metricz.put("beta",   new StockFeat.Metrics(365) { public double calc() { return info.dr.beta(Context.spydr); } });
	   metricz.put("alpha",  new StockFeat.Metrics(365) { public double calc() { return info.ts.alpharoi(Context.spyts,info.dr.beta(Context.spydr)); } });

	   metricz.put("mktrscore", new StockFeat.Metrics(365) { public double calc() { return Context.spyts.rangescore(); } });
	   metricz.put("mktdip", 	new StockFeat.Metrics(365) { public double calc() { return info.dipscore(Context.spyts,todt, 7); } });
	   metricz.put("mktsindex", new StockFeat.Metrics()    { public double calc() { return Context.spyts.seasonindex((todt/100)%100); } });
	}

	public static double dipscore(TimeSeries ts, int todt, int days) {
		try {
			//TimeSeries met = WinTransform.pctchg(ts,days).timeseries();
			TimeSeries met = new TimeSeries(Transforms.compute(new TimeSeries[] {ts}, days, Transforms.PCTCHG));
			met.setWindow(Dates.todate(Dates.toindex(todt)-100), todt);
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

	public static PortBuilder etfbuild(String stks) {
		return etfbuild(stks,true);
	}

	public static PortBuilder etfbuild(String stks, boolean mwvalid) {
		final Set<String> aset = new HashSet<String>();
		for (String stk:stks.toUpperCase().split(","))
			aset.add(stk);
		return new PortBuilder(mwvalid) {
			public boolean filter() {
				return aset.contains(stk);
			}
		};
	}

	public static PortBuilder etfxbuild(String stks) {
		final Set<String> aset = new HashSet<String>();
		for (String stk:stks.toUpperCase().split(","))
			aset.add(stk);
		return new PortBuilder(true) {
			public boolean filter() {
				return (aset.contains(stk))? false : true;
			}
		};
	}

	static Map<String,StockFeat> map = new HashMap<String,StockFeat>();
	static Map<String,PortBuilder> builder = new TreeMap<String,PortBuilder>();

	public static void loadPortfolio() throws Exception
	{
		for (String stk: Context.symbols.keySet()) {
			try {
				map.put(stk,new StockFeat(stk));
				//System.out.println(Context.dim(stk,"sharesoutstanding"));
			} catch (Exception e) {
				System.err.println("Failed for ticker " + stk);
			}
		}

		builder.put("MKT", new PortBuilder(true) { public boolean filter() { return true; } });
		builder.put("TECH", new PortBuilder(true) { public boolean filter() { return Context.dim(stk,"sector").equals("TECH"); } });
		builder.put("FINANCE", new PortBuilder(true) { public boolean filter() { return Context.dim(stk,"sector").equals("FINANCE"); } });
		builder.put("REIT", new PortBuilder(true) { public boolean filter() { return Context.dim(stk,"sector").equals("REIT"); } });
		builder.put("MANUFACTURING", new PortBuilder(true) { public boolean filter() { return Context.dim(stk,"sector").equals("MANUFACTURING"); } });
		builder.put("HEALTH", new PortBuilder(true) { public boolean filter() { return Context.dim(stk,"sector").equals("HEALTH"); } });
		builder.put("ENERGY", new PortBuilder(true) { public boolean filter() { return Context.dim(stk,"sector").equals("ENERGY"); } });
		builder.put("TRADE", new PortBuilder(true) { public boolean filter() { return Context.dim(stk,"sector").equals("TRADE"); } });

		builder.put("SMALLCAP", new PortBuilder(true) { public boolean filter() {
			 	double d = Double.parseDouble(Context.dim(stk,"sharesoutstanding"));
				return  (feat.lastpx * d) < 10_000_000_000.0;
			} });
		builder.put("MIDCAP", new PortBuilder(true) { public boolean filter() {
			 	double d = Double.parseDouble(Context.dim(stk,"sharesoutstanding"));
				return  (feat.lastpx * d) > 10_000_000_000.0 && (feat.lastpx * d) < 100_000_000_000.0;
			} });
		builder.put("LARGECAP", new PortBuilder(true) { public boolean filter() {
			 	double d = Double.parseDouble(Context.dim(stk,"sharesoutstanding"));
				return  (feat.lastpx * d) > 100_000_000_000.0;
			} });

		builder.put("XFANG", etfxbuild("AAPL,MSFT,GOOG,AMZN,TSLA,NVDA,META,TSLA,AVGO,TSM,NFLX"));

		builder.put("FANG", etfbuild("AAPL,MSFT,GOOG,AMZN"));
		builder.put("SEMI", etfbuild("ANET,AMAT,TXN,NVDA,MU,AMD,INTC,AMAT,RBLX"));
		builder.put("CHINA", etfbuild("BABA,JD,PDD,DIDI,BIDU,TCEHY,TAL,EDU,DQ,JKS,NIO,XPEV,VIPS"));
		builder.put("ARKK", etfbuild("TDOC,DOCU,DKNG,TSLA,ZOOM,ROKU,COIN,SPOT,TWLO,EXAS,SHOP,U,PATH"));
		builder.put("CRUSH", etfbuild("SHOP,TSLA,NFLX,NVDA,PYPL,FB,AMD,INTC,BABA,PLTR,ROKU,SNOW,OKTA,SQ,DASH"));
		builder.put("FOOD", etfbuild("ADM,BG,BUD,CAG,CPB,DAR,GIS,HRL,HSY,INGR,K,KHC,LW,MDLZ,POST,PPC,SJM,TAP,MKC"));
		builder.put("INDIA", etfbuild("HDB,INFY,WIT,TTM,CTSH,SYFY,AZRE,WNS"));
		builder.put("OIL", etfbuild("NEE,COP,TRP,PTR,EC,VLO,XOM,CVX,BP,EOG,SU,NRG,WES"));
        builder.put("AIR", etfbuild("AAL,DAL,LUV,JBLU,UAL,SAVE,ALK,BA,ALGT,SKYW"));
		builder.put("AAA", etfbuild("TSBK,MKC,OGS,CHD,PNW,SEIC,CHKP,PLBC,RHI,EBSB,XEL,YORW,ELS,GNTX,ABCB,CBAN,PAYX,ASB,MNST,MA,NVR,CSCO,BRO,PCSB,LNT,APH,MANH,EBTC,UNP,AME,ANSS,ADP,AAPL,CATY,VRSK,BK,MMM,OVLY,EXPO,BR,SFST,STT,CP,VCTR,NDAQ,FIZZ,WEC,UL,GGG,ROL,FAST,PBIP,ORCL,TBNK,JKHY"));
	    builder.put("AA", etfbuild("AX,IDA,TD,CPK,INMD,FDS,BABA,HON,CCB,FRC,TXN,BXS,PBCT,AMGN,FMAO,BCPC,CNPAY,QLYS,PRI,GBCI,FR,TROW,EA,USNA,IEX,MAS,CTSH,BKSC,ITW,GCBC,BFC,MKTX,FCAP,UNH,CGNX,HD,IRCP,WASH,AROW,AYI,MPX,EDU,ITIC,SYBT,WBS,CHCO,ALTA,SMMF,AMZN,AMP,ICLR,RGCO,CCBG,FFIV,INTU,MSCI,FDBC,ISRG,BMO,TDY,CE,ABTX,ZTS,CVCY,CACC,CIVB,ADBE,ACN,ERIE,ROK,ANET,MPWR,BFST,PKBK,TMO,GOOG"));
	    builder.put("A", etfbuild("REGN,ATHM,PAC,FB,JOBS,SBNY,AZPN,IX,DSGX,MTD,PAYC,RY,ESQ,MS,ESNT,HEI,SNA,BUSE,SFNC,FBHS,NICE,TYL,PNFP,KL,CCF,FAF,TRMB,HELE,COLB,PKG,CDNS,SCHW,ABMD,FTNT,NMIH,SIVB,CNBKA,NVDA,CTBI,DTE,SWX,LII"));
		builder.put("STEADY", etfbuild("ACGL,BNS,PSA,JNPR,UNH,CVS,CB,BMO,AVB,PGR,KO,HSY,ICE,CME"));
		builder.put("BUFFET", etfbuild("AAPL,BAC,AXP,KO,KHZ"));
		builder.put("COMMODITY", etfbuild("GLD,SLV,USO,CPER,BNO,WEAT,SOYB,RJA,RJI", false));
        builder.put("CRYPTO", etfbuild("COIN,RIOT,SQ,PYPL,MSTR,SI,SOFI,HOOD"));
		builder.put("AIML", etfbuild("AAPL,MSFT,GOOG,AMZN,NVDA,META,AI,AVGO,AMD,ANET,MRVL,MPWR,SYM,MU,TSLA"));
		builder.put("EVMON", etfbuild("MSFT,GOOG,LUV,META,HD,AXP,BRK,AAPL,V,MA,BABA,LOW,MCD,DIS,ADBE,NKE,RACE,LVMH,TROW,SHW,GNRC,SBUX,OTIS,INTU,COST,TGT,TJX,BAM,SFM,CTAS,JNJ,PG,HSY"));

		for (String stk:map.keySet())
			for (PortBuilder b:builder.values())
				b.process(stk,map.get(stk));
	}

	public static void main(String[] args) throws Exception
    {
		execute();
	}

    public static void execute() throws Exception
    {
		Context.init();
		StockFeat.init();
		StockFeat.loadPortfolio();

		BufferedWriter writer = new BufferedWriter(new FileWriter(Const.path+"port.csv"));
		writer.write(StockFeat.header()+",wgtype");
		writer.newLine();

		StockFeat feat = null;
		for (String stk:builder.keySet()) {
			//if (!stk.equals("REIT")) continue;
			//builder.get(stk).mw().print();
			if (builder.get(stk).mw() != null) {
				feat = new StockFeat(stk+"/MW", builder.get(stk).mw().load(new TreeMap<Integer,Double>()));
				writer.write(feat+",MW");
				writer.newLine();
			}
			feat = new StockFeat(stk+"/EW", builder.get(stk).ew().load(new TreeMap<Integer,Double>()));
			writer.write(feat+",EW");
			writer.newLine();
		}
		writer.close();
	}

}
