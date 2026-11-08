package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.stream.*;
import com.google.gson.stream.JsonReader;

import java.text.DecimalFormat;
import java.util.concurrent.*;
import java.util.Calendar;

public class StockReportSvc
{
	// PORTED: computation half of legacy StockReportSvc (METRIC / PRICE / OHLC / SHADOW / INIT).
	// The socket loop (main/processIO), LOGIN/TRADE/QRY/CSV handlers and reload() were replaced by
	// com.mystock.api.* ; the methods below are verbatim. Callers must hold com.mystock.LegacyLock.

	static DecimalFormat df = new DecimalFormat("#.####");
	static Map<String,Integer> sym2idx;
	static Map<Integer,String> idx2sym;
	static Map<String,String> symbols2;
	static StockDB sdb;
   	static PriceDB pdb;
	static TradesDB tdb;
   	static LoginDB ldb;
   	static TimeSeries spydr;
   	static TimeSeries spyts;
	static int dbdate;

	public enum Metrics {
		OPEN,HIGH,LOW,PREV,MKTPX,RSI,
	    STDMKTPX,ROI,DAYPNL,WEEKPNL,MOMPNL,QOQPNL,YOYPNL,
		DTPM,STLPM,OPTVOL,OPTIVOL,EMA20,EMA60,EMA200,RANGESCORE,
		LASTDEVZ,CORR,BUMPINESS,PRSCORE,KURT,CCI,CCIQ,
		COSKEW,PCTCHG,ATR,ADV,TRMON,PNLDIR,MOMDIP
	}

    private static int MIN_DT = 20201231;

	//static { init(); }

	public static void init() {
		 try {
		   //System.out.println("Init called");
		   sdb = new StockDB();
	 	   pdb = new PriceDB(true);
		   tdb = new TradesDB();
		   ldb = new LoginDB();
		   Context.init();
		   String filter = null;
		   for (String sym:Context.etfs)
		   		filter = (filter == null)? "'" + sym + "'":filter + ",'" + sym + "'";
		   filter = "CAST(MarketCapitalization as decimal) > 1e9 or ticker in ("+filter+")";
	 	   symbols2 = sdb.load("ticker,Name,Sector,Industry,MktSize",
		   			"MarketCapitalization,PERatio,EPS,DividendYield");
	 	   sym2idx = sdb.load(filter);
		   idx2sym = new HashMap<Integer,String>();
		   sym2idx.entrySet().forEach(e -> idx2sym.put(e.getValue(),e.getKey()));
		   TreeMap<Integer,Double> dt2px = new TreeMap<Integer,Double>();
	 	   pdb.load(sym2idx.get(Const.MKTETF),dt2px);
		   dbdate = dt2px.lastKey();
		   Dates.init(20150101,dbdate);
		   spydr = TimeSeries.buildDR(dt2px);
		   spydr.setWindow(MIN_DT,Dates.dates[Dates.dates.length-1]);
		   spyts = TimeSeries.buildStdMktPx(dt2px);
		   spyts.setWindow(MIN_DT,Dates.dates[Dates.dates.length-1]);
		   StockFeat.init();
		   StockFeat.loadPortfolio();
		   System.out.println("Init complete");
		 } catch (Exception e) {
			 e.printStackTrace();
		 }
	}

   //SHADOW,STOCK,INITDT,PRICEVOL,RISKVOL
   public static void shadowPortfolio(String[] csv, PrintWriter out) throws Exception
   {
		int idt = Integer.parseInt(csv[2].replaceAll("-", ""));
		ICBackTest m = ICStrategies.select("reversion",csv[1].toUpperCase(),45); 
		m.feat.set(idt);

		List<String> csvdata = new ArrayList<String>(); 
		String vol = csv[3].trim() + "," + csv[4].trim();
		if (vol.equals(",")) vol = null;
		ICBackTest.execute(m, idt, vol,  null, csvdata);
		out.println(m.logheader());
		for (String csvstr:csvdata) 
			out.println(csvstr);
   }

   //OHLC,fld(open|close|adv|high|low|prev),start,end,ticker(s)
   // if start==end, tickers can be multiple
   public static Map<Integer,Double> processOHLC(String[] csv) throws Exception
   {
	   Map<Integer,Double> out;
	   TreeMap<Integer,Double> res = new TreeMap<Integer,Double>();
	   int start = Integer.parseInt(csv[2]);
	   int end = Integer.parseInt(csv[3]);
	   if (start == end) {
		   pdb.loadAsof(csv[1], start, res);
		   if (res.size() == 0) {
			   start = Context.findTradingDay(start,1);
			   pdb.loadAsof(csv[1], start, res);
		   }
		   out = new LinkedHashMap<Integer,Double>();
		   for (int i=4;i < csv.length;i++) {
			   Integer symid = sym2idx.get(csv[i]);
			   if (symid == null || res.get(symid) == null)
			   		out.put(i-3,0.0);
			   else out.put(i-3,res.get(symid));
		   }
	   } else {
		   pdb.loadField(sym2idx.get(csv[4]), csv[1], start, end, res);
		   out = res;
	   }
		if (!csv[1].toLowerCase().equals("adv"))
			 for (int key:out.keySet())
				 out.put(key,out.get(key)/100.0);
		return out;
   }

   //PRICE,ticker,type(STK\EWT\MWT)
   public static TreeMap<Integer,Double> processTimeSeries(String[] csv) throws Exception
   {
	   TreeMap<Integer,Double> res = new TreeMap<Integer,Double>();
	   if (!csv[2].equals("STK")) {
		   if (StockFeat.builder.containsKey(csv[1])) {
			   if (csv[2].equals("EWT"))
					StockFeat.builder.get(csv[1]).ew().load(res);
			   else
				    StockFeat.builder.get(csv[1]).mw().load(res);

			   if (csv[2].equals("EWT") || csv[2].equals("MWT")) {
				   StockFeat feat = new StockFeat(csv[1], res);
				   feat.ts.values.load(res);
			   }
		   }
	   } else {
		   if (StockFeat.map.containsKey(csv[1]))
		   		StockFeat.map.get(csv[1]).ts.values.load(res);
	   }
	   System.out.println("Timeseries:" + res.size());
	   return res;
   }

   public static TreeMap<Integer,Double> getDailyMatrics(String sym, Metrics m)
   throws Exception
   {
	   int symid = sym2idx.get(sym);
	   TreeMap<Integer,Double> res = new TreeMap<Integer,Double>();
	   if (m == Metrics.ATR) {
	   		res.clear();
	   		res.putAll(pdb.ohlc(symid).atr(0));
	   } else if (m == Metrics.PCTCHG) {
		   res.clear();
		   res.putAll(pdb.ohlc(symid).eodpctchg(0));
	   } else if (m == Metrics.CCI || m == Metrics.CCIQ) {
			res.clear();
			StockFeature f = new StockFeature(sym);
			int lastdt = Dates.lastdt();
			for (int dt = lastdt - 10000; dt <= lastdt; dt = Dates.offset(dt,1)) {
				f.set(dt);
				res.put(dt,(m == Metrics.CCI)? f.cci():f.cciq());
			}
			return res;
	 } else if (m == Metrics.MOMDIP) {
		   res.clear();
		   StockFeature f = new StockFeature(sym);
		   int lastdt = Dates.lastdt();
		   for (int dt = lastdt - 10000; dt <= lastdt; dt = Dates.offset(dt,1)) {
			   f.set(dt);
			   res.put(dt,f.momdip());
		   }
		   return res;
	   }
	   else if (m == Metrics.ADV)
		   pdb.loadField(symid, "adv", 20150101, 20990101, res);
	   else return res;
	   TimeSeries ser = TimeSeries.build(res);
	   for (int i=0;i < Dates.dates.length;i++)
	   {
		   int dt = Dates.dates[i];
		   if (m == Metrics.ADV)
		   		res.put(dt,(ser.sma(dt,3)/ser.sma(dt,100)));
		   else
		   		res.put(dt,ser.sma(dt,3));
	   }
	   return res;
   }

   //cmd,ticker,metric,timespan,params,param0
   public static TreeMap<Integer,Double> processMetrics(String[] csv)
	 		throws Exception
   {
	   String ticker = csv[1];
	   Metrics m = Metrics.valueOf(csv[2]);
	   switch (m) {
		   case PCTCHG:  return getDailyMatrics(ticker,m);
		   case ATR:  return getDailyMatrics(ticker,m);
		   case ADV:  return getDailyMatrics(ticker,m);
		   case MOMDIP: return getDailyMatrics(ticker, m);
		   case CCI: return getDailyMatrics(ticker, m);
		   case CCIQ: return getDailyMatrics(ticker, m);
	   }
	   int timespan = Integer.parseInt(csv[3]);
	   TreeMap<Integer,Double> res = new TreeMap<Integer,Double>();
	   pdb.load(sym2idx.get(ticker),res);
	   if (res.size()==0) return res;
	   TimeSeries px = TimeSeries.build(res);
	   TimeSeries dr = TimeSeries.buildDR(res);
	   TimeSeries ts = TimeSeries.buildStdMktPx(res);

	   res.clear();
	    switch (m) {
	   	   	case DAYPNL:  timespan = 3; break;
	   	   	case WEEKPNL: timespan = 10; break;
	   	   	case MOMPNL:  timespan = 35; break;
	   	   	case QOQPNL:  timespan = 100; break;
	   	   	case YOYPNL:  timespan = 365; break;
			case TRMON:   timespan = 30; break;
			case DTPM:    timespan = 35; break;
			case STLPM:   timespan = 35; break;
			case OPTVOL:  timespan = 35; break;
			case OPTIVOL:  timespan = 35; break;
			case EMA20:   timespan = 20; break;
			case EMA60:   timespan = 60; break;
			case EMA200:  timespan = 250; break;
			case COSKEW:  timespan = 30; break;
			case PNLDIR: timespan = 10; break;
			case ROI:
			case RSI:
			case RANGESCORE:
			case LASTDEVZ:
			case PRSCORE:
			case CORR:
			case KURT:
			case BUMPINESS:
				timespan = 365;
				break;
   		}

	   for (int i=0;i<Dates.dates.length-timespan;i++) {
		    int asof = Dates.dates[i+timespan];
			ts.setWindow(Dates.dates[i],asof);
			if (m == Metrics.PRSCORE) ts.setWindow(asof,Const.PR_SCORE);
			dr.setWindow(ts);
			px.setWindow(ts);
			spydr.setWindow(ts);
			//System.out.println(Dates.dates[i]+","+asof+","+ts.pctchg(7));
	 	    switch (m) {
			   case MKTPX:   res.put(asof,ts.asof(asof));break;
			   case ROI:     res.put(asof,ts.cagr()); break;
			   case RSI:     res.put(asof,ts.rsi()); break;
	 	   	   case DAYPNL:  res.put(asof,ts.pctchg(1)); break;
	 	   	   case WEEKPNL: res.put(asof,ts.pctchg(7)); break;
	 	   	   case MOMPNL:  res.put(asof,ts.pctchg(31)); break;
	 	   	   case QOQPNL:  res.put(asof,ts.pctchg(93)); break;
	 	   	   case YOYPNL:  res.put(asof,ts.pctchg(365)); break;
			   case DTPM:    res.put(asof,dr.downtickspermonth(0)); break;
			   case STLPM:   res.put(asof,dr.downtickspermonth(2)); break;
			   case OPTVOL:  res.put(asof,ts.optvol()); break;
			   case OPTIVOL:  res.put(asof,ts.optivol(0)); break;
			   case KURT:    res.put(asof,ts.kurtosis()); break;
			   case EMA20:   res.put(asof,ts.ema(asof,20)); break;
			   case EMA60:   res.put(asof,ts.ema(asof,60)); break;
			   case EMA200:  res.put(asof,ts.ema(asof,200)); break;
			   case TRMON:   res.put(asof,ts.rangescore()/100.0); break;
			   case RANGESCORE: res.put(asof,ts.rangescore()); break;
			   case LASTDEVZ:  	res.put(asof,ts.lastdevz()); break;
			   case CORR:  		res.put(asof,dr.corr(spydr)); break;
			   case COSKEW:     res.put(asof,dr.coskew(spydr)); break;
			   case BUMPINESS:  res.put(asof,ts.bumpiness()); break;
			   case PRSCORE:    res.put(asof,ts.rangescore()); break;
			   case PNLDIR:     res.put(asof,dr.ema(asof,10)-100.0); break;
	 	    }
	   }
	   System.out.println("Timeseries:" + res.size());
	   return res;
   }

}
