package com.mystock.legacy;

import java.io.*;

import java.io.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.LinkedList;

import java.net.*;

//Goal: Backtest various strategies derived from Iron Condors
public abstract class ICBackTest
{
	//Default days to expiry
	static int DEFAULT_DTE       = 45;
	//Default trade quantity
	static int DEFAULT_QTY       = 5;
	//Short delta targeted when rolling adjustment is done
	static double SHORT_DELTA    = 0.20;
	//Default distance between short & long distance of IC
	static double DEFAULT_WIDTH  = 10.0;
	//This is added to realized vol to get riskvol to use for width adjustment calc
	static double RISKVOL_OFFSET = 0.10;

	//strikepx,opttrdpx,qty,dte
	protected List<double[]> trades;

	protected double upx; //spot underlying px
	//Vol used for calculating trade price when opening and closing contracts
	//High IVR leads to collecting more credits and higher profits
	protected double vol;
	// Vol used for delta based strike price calcs during auto-adjust
	// Higher vol increases safety distance applied when rolling.
	// Good start is about 0.10 + max(optvol,histvol)
	protected double riskvol;
	//spot dte starting at 45 and ending at 0
	protected int dte;
	//Dt when stgy started running 
	protected int initdt;

	protected int initdte;
	protected double creditPerDay = 0;

	protected double initupx; //Initial underlying px
	protected double width;  // Actual Width of IC
	protected double spread; // Actual Spread of IC

	protected  StockFeature feat;
	protected  TimeSeriesData tsdc;
	protected  TimeSeriesData tsdl;
	protected  TimeSeriesData tsdh;

	public ICBackTest(String stk, int dte)
		throws Exception
	{
		this.feat     = new StockFeature(stk);
		this.feat.ref(new StockFeature("SPY"));
		this.tsdc     = new TimeSeriesData(feat.price().eodpx(0));
		this.tsdl     = new TimeSeriesData(feat.price().lowpx(0));
		this.tsdh     = new TimeSeriesData(feat.price().highpx(0));
		//Uses impliedvol to get most accurate pricing.
		//double[] d    = PVCache.register(stk);
		//this.vol      = d[1]/100.0;
		this.vol      = 0.05+Math.max(feat.optvol(),feat.histvol())/100.0;
		Context.log("Trade Vol Used",vol);

		this.riskvol  = this.vol;
		this.initdte  = dte;
		this.dte      = dte;
		//this.strikes  = new ArrayList<Double>();
		//this.trdpx    = new ArrayList<Double>();
		//this.qty      = new ArrayList<Integer>();
		//this.maturity = new ArrayList<Integer>();
		this.trades   = new ArrayList<double[]>();
	}

	public void init(int initdt, double lp, double sp, double upx, double sc, double lc) {
		this.upx = upx;
		this.initdt = initdt;
		if (lp > 0) trade(-1*lp,5);
		if (sp > 0) trade(-1*sp,-5);
		if (lc > 0) trade(lc,5);
		if (sc > 0) trade(sc,-5);
		this.initupx = this.upx;
		this.spread  = Math.max(upx - sp, sc - upx);
		this.width   = Math.max(sp - lp, lc - sc);
		Context.log("Inital",this.initupx,this.spread,this.width);
		pnl(2);
		creditPerDay = -100.0 * icpx() / dte; 
		initic();
	}

	public double pnl(int verbose)
	{
		double pnl = 0;
		Map<Double,double[]> sizeflow = net_trades_by_price();
		Map<Double,Double> prices = new HashMap<Double,Double>();
		int[] trdkeys = last_trade_by_ic_legs(sizeflow);

		// Net of inflows and outflows
		for (Double key:sizeflow.keySet()) 
			pnl -= sizeflow.get(key)[1];
		
		// Value of open inventory
		for (int trdkey: trdkeys) {
			if (trdkey < 0) continue;
			BSOption o = option(trdkey);
			for (Double key:sizeflow.keySet()) {
				if (Math.abs(key) != o.K) continue;
				prices.put(key,o.computePrice());
				pnl += sizeflow.get(key)[0] * o.computePrice();
			}	
		}
		
		if (verbose > 0) {
			for (Double key:sizeflow.keySet()) {
				double[] val = sizeflow.get(key);
				double px = (prices.containsKey(key))? prices.get(key):0; 
				Context.log("Pos", key, val[0], val[1], px, val[0]*px - val[1]);
				for (int i=0;i < trades.size(); i++)
					if (verbose == 2 && trades.get(i)[0] == key)
						Context.log("\tTrade", trades.get(i)[3], trades.get(i)[2], trades.get(i)[1]);
			}
			for (int trdkey: trdkeys) 
				if (trdkey != -1 && prices.containsKey(trades.get(trdkey)[0]))
					Context.log("Legs Open ", trades.get(trdkey)[0]); 	
		}
		return pnl*100.0;
	}

	public void bookAutoAdjustTrade(double origpx, int iqty, double newpx) {
		String msg = "\t\t\t\t>>>>";
		msg += (newpx == 0)? "Closing ":"Rolling ";
		msg += (iqty < 0)? "Short ":"Long ";
		msg += (origpx < 0)? "Put":"Call";
		Context.log(msg, origpx, newpx, iqty);
		trade(origpx,-1 * iqty);
		if (newpx != 0) trade(Math.floor(newpx),iqty);
		if (newpx == 0) {
			Context.log("Breach Warning !!. Exit ASAP");
		}
	}

	//strike > 0 = call, qty > 0 = Buy
	public void trade(double strikepx, int iqty)
	{
		if (strikepx == 0) return;
		//maturity.add(dte);strikes.add(strikepx);qty.add(iqty);
		char cp = (strikepx > 0)? 'c':'p';
		double idte = dte*1.0/365.0;
		BSOption b = new BSOption(upx, Math.abs(strikepx), idte, vol, cp);
		//trdpx.add(b.computePrice());
		double[] values = new double[4];
		values[0] = strikepx;
		values[1] = b.computePrice();
		values[2] = iqty;
		values[3] = dte;
		this.trades.add(values);
	}

	//Called once for each day with eod prices for the day
	public void tick(int ymd)
	{
		Context.log("---------");
		if (dte == 0 || ymd > Dates.lastdt()) {
			Context.log("Strategy expired or no more data", dte, ymd);
			return;
		}
		double[] lch = new double[] {tsdl.get(Dates.toindex(ymd)),
				tsdc.get(Dates.toindex(ymd)),tsdh.get(Dates.toindex(ymd))};

		feat.set(ymd);
		//double pnldir = feat.pnldir();

		//idx = Dates.dates.length + idx - 46;
		String pxs = upx + " -> " + lch[0] + "/" + lch[1] + "/" + lch[2];
		//Context.log("NewTick", ymd + "(" + dte + ")", pxs);
		upx = lch[1];
		double pnl = pnl(0);
		Context.log("Price", upx, "NetPrice", icpx(), "NetDelta", icdelta(), "Day", (initdte-dte) , "PNL", pnl, "Excess", pnl - (initdte-dte)*creditPerDay);
		AutoAdjust();
		//if (AutoAdjust())
		//	Context.log("Adjusted: NetPrice", icpx(), "NetDelta", icdelta(), "PNL", pnl(0));
		dte--; //= 46 - Dates.dates.length + idx;
	}

	public String logheader() {
		return "upx,vol,risk,days,lput,sput,scall,lcall,netpx,netdelta,pnl,excesspnl";
	}
	public String log() {
		double pnl = pnl(0);
		Map<Double,double[]> sizeflow = net_trades_by_price();
		int[] trdidx = last_trade_by_ic_legs(sizeflow);
		double[] px = {0,0,0,0};
		if (trdidx[0] >= 0) px[0] = Math.abs(trades.get(trdidx[0])[0]);
		if (trdidx[1] >= 0) px[1] = Math.abs(trades.get(trdidx[1])[0]);
		if (trdidx[2] >= 0) px[2] = Math.abs(trades.get(trdidx[2])[0]);
		if (trdidx[3] >= 0) px[3] = Math.abs(trades.get(trdidx[3])[0]);
		return upx + "," + this.vol + ","  + this.riskvol + "," + (initdte-dte) + "," + 
			px[0] + "," + px[1] + "," + px[2] + "," + px[3] + "," + icpx() +  "," + 
			icdelta() + "," + pnl + "," + (pnl - ((initdte-dte)*creditPerDay));
	}

	public void initic() {}
	public abstract boolean AutoAdjust();

	public void setRiskVol(double vol) {
		this.riskvol = vol;
	}

	private String readLine()
	{
		try {
			System.out.print("Add Manual Trade(s) - Strike,Qty : ");
			BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
			String res = reader.readLine();
			if (res.equals("?")) {
				pnl(2);
				return res;
			}
			return (res.split(",").length != 2)? "0,0":res;
		} catch (Exception e) {
			return "0,0";
		}
	}

	protected Map<Double,double[]> net_trades_by_price() 
	{
		Map<Double,double[]> sizeflow = new HashMap<Double,double[]>();
		//net the trades at every strike
		for (int i=0;i < trades.size(); i++)
		{
			Double   key = trades.get(i)[0];
			double[] val = sizeflow.get(key);
			if (val == null) sizeflow.put(key, val = new double[] {0,0});
			double[] trd = trades.get(i);
			val[0] += trd[2];
			val[1] += (trd[1]*trd[2]);
		}
		return sizeflow;
	}

	protected int[] last_trade_by_ic_legs(Map<Double,double[]> sizeflow) {
		int[] trdkeys = new int[] {-1,-1,-1,-1};
		//Assort them into 4 legs of IC. Long/short and put/call
		for (Double key:sizeflow.keySet())
		{
			double[] val = sizeflow.get(key);
			if (val[0] == 0) continue;
			int tradekey = -1;
			for (int i=0;i < trades.size(); i++)
				if (trades.get(i)[0] == key.doubleValue())
					tradekey = i;
			if (val[0] > 0 && key < 0) trdkeys[0] = tradekey;
			if (val[0] < 0 && key < 0) trdkeys[1] = tradekey;
			if (val[0] < 0 && key > 0) trdkeys[2] = tradekey;
			if (val[0] > 0 && key > 0) trdkeys[3] = tradekey;
		}
		return trdkeys;
	} 

	private void ManualAdjust()
	{
		//Checks current deltas and makes adjustment trades necessary
		while (true)
		{
			String[] arr = readLine().split(",");
			if (arr[0].equals("?")) continue;
			trade(Double.parseDouble(arr[0]),Integer.parseInt(arr[1]));
			if (arr[0].equals("0")) break;
		}
	}

	//Returns the option corresponding to a trade record with spot price and dte
	protected BSOption option(int idx)
	{
		double idte = dte*1.0/365.0;
		double strk = trades.get(idx)[0];
		char cp = (strk > 0)? 'c':'p';
		//Context.log("OX Param:",upx, Math.abs(strk), dte, vol, cp);
		return new BSOption(upx, Math.abs(strk), idte, vol, cp);
	}

	protected BSOption optionrisk(double strk) {
		double idte = dte*1.0/365.0;
		char cp = (strk > 0)? 'c':'p';
		return new BSOption(upx, Math.abs(strk), idte, riskvol, cp);
	}

	protected BSOption optionrisk(int idx)
	{
		double idte = dte*1.0/365.0;
		double strk = trades.get(idx)[0];
		char cp = (strk > 0)? 'c':'p';
		return new BSOption(upx, Math.abs(strk), idte, riskvol, cp);
	}

	//Returns net price of all open positions
	public double icpx()
	{
		double res = 0;
		for (int i=0;i < trades.size(); i++)
			res += option(i).computePrice() * trades.get(i)[2];
		return res;
    }

	//Returns net delta of all open positions
	public double icdelta()
	{
		double res = 0;
		int type = BSOption.DELTA;
		for (int i=0;i < trades.size(); i++)
			res += option(i).computeGreeks()[type] * trades.get(i)[2];
			//System.out.println(res+","+d[type]+","+qty.get(i)+","+strk+","+upx);
		return res;
    }

	//args: stk optvol,riskvol initdt,expdt pricecsv
	public static void main(String[] args) throws Exception
	{
		Context.log("./ICBackTest","StdIC","stock","initdt,expdt","optvol,riskvol","lput,sput,upx,scall,lcall");
		Const.path = "./data/";
		Context.init();
		//load stock, prices and init date
		String stgy = args[0];
		String stk = args[1]; //ticker

		String dts = (args.length > 2)? args[2]:"0";

		String vols = (args.length > 3)? args[3]:"0";
		if ("0".equals(vols)) vols = null;

		String prices = (args.length > 4)? args[4]:null;

		System.out.println(stgy+","+stk+","+dts+","+vols+","+prices);

		try {
			if (dts == null) dts = "0";
			int idts = Integer.parseInt(dts);
			if (idts < 20200101)
				idts = Dates.dates[Dates.dates.length-1-idts];
			dts = idts+","+Dates.offset(idts, 45);
		} catch (Exception e) {}
		//dts = Dates.dates[Dates.dates.length-DEFAULT_DTE-idts] + "," + Dates.dates[Dates.dates.length-1];

		String[] arrdt = dts.split(",");
		int dte = Dates.dateToDays(Integer.parseInt(arrdt[1]));
		dte -= Dates.dateToDays(Integer.parseInt(arrdt[0]));

		ICBackTest m = ICStrategies.select(stgy,stk,dte); 
		m.feat.set(Integer.parseInt(arrdt[0]));
	
		int initdt = Integer.parseInt(arrdt[0]);
		List<String> log = new ArrayList<String>();
		ICBackTest.execute(m, initdt, vols,  prices, log);
		for (String s:log)
			System.out.println(s);
	}

	public static void main1(String[] args) throws Exception
	{
		Const.path = "./data/";
		Context.init();
		int initdt = Dates.dates[Dates.dates.length-DEFAULT_DTE-1];
		String dts = initdt + "," + Dates.dates[Dates.dates.length-1];
		Map<String,Double> pnls = new HashMap<String,Double>();
		String syms = "FTNT,SHEL,BABA,SCHW,TTE,SHOP,UBER,USO,SBUX,PM,TLT,XLE,RTX,MRNA,BIDU,PDD,DIS,ORCL,XLV,SNOW,GOOG,GE,XOP," + 
			"CVX,ABNB,TSLA,AAPL,AMD,TGT,BA,AMZN,IBM,IWM,PGR,GLD,AXP,XSD,V,ANET,ALGN,CAT,DIA,GS,MSFT,QQQ,MA,ADBE,SPY,META,NFLX,COST,NVDA,SMCI";
				
		for (String stk:syms.split(",")) {
			try {
				Context.log("Processing",stk);
				ICBackTest m = ICStrategies.select("stdic",stk, DEFAULT_DTE);
				m.feat.set(initdt);
				double pnl = ICBackTest.execute(m, initdt, null,  null, null);
				pnls.put(stk,pnl);
			} catch (Exception e) {
				Context.log("Error processing",stk, e.getMessage());
			}
		}

		double totalpnl = 0, negpnl = 0, negs = 0;
		for (String stk:syms.split(",")) {
			Double pnl = pnls.get(stk);
			if (pnl == null) pnl = 0.0;
			else if (pnl.isNaN()) pnl = 0.0; 
			totalpnl += pnl;
			Context.log("Stock",stk,"PNL",pnl);
			if (pnl > 0)  continue;
			negs++;
			negpnl += pnl;
		}
		Context.log("Total",totalpnl,pnls.size());
		Context.log("Green",totalpnl-negpnl,pnls.size()-negs);
		Context.log("Red  ",negpnl,negs);
	}

	public static double get_ic_min_width(double last,double vol) {
		int px = (int)(last/100.0); 
		switch (px) {
			case 0: return (vol > 0.30)? 10:5;
			case 1: return (vol > 0.30)? 15:10;
			case 2: return (vol > 0.30)? 20:15;
			case 3: return (vol > 0.20)? 25:20;
			case 4: 
				if (vol > 0.30) return 25;
				return (vol > 0.20)? 20 : 15; 
		}
		if (vol > 0.30) return 30;
		return (vol > 0.20)? 25 : 20; 
	}

	//Executes a 45 DTE strategy from initdt
	//Args: stk initdt riskvol pricecsv=lp,sp,upx,sc,lc
	// riskvol = 0 makes code estimate it dynamically
	public static double execute(ICBackTest m, int initdt, String volcsv, String pricecsv, List<String> log) throws Exception
	{
		double riskvolused = 0, optvolused = 0;
		if (volcsv != null) {
			String[] vols = volcsv.split(",");
			optvolused = Double.parseDouble(vols[0]);
			riskvolused = Double.parseDouble(vols[1]);
		} else {
			riskvolused = RISKVOL_OFFSET+Math.max(m.feat.optvol(),m.feat.histvol())/100.0;
			//Uses impliedvol to get most accurate pricing.
			//double[] d    = PVCache.register(stk);
			//this.vol      = d[1]/100.0;
			optvolused  = 0.05+Math.max(m.feat.optvol(),m.feat.histvol())/100.0;
		}
		m.setRiskVol(riskvolused);
		m.vol = optvolused;

		Context.log("Vol used ", optvolused, riskvolused);
		//start by default 46 days back
		int cnt = 0;
		if (pricecsv != null)
		{
			double[] prices = new double[5];
			for (String px:pricecsv.split(","))
				prices[cnt++] = Double.parseDouble(px);
			m.init(initdt,prices[0],prices[1],prices[2],prices[3],prices[4]);
		} else {
			double[] prices = new double[5];
			prices[2] = m.tsdc.get(Dates.toindex(initdt));
			BSOption b = new BSOption(prices[2],prices[2],(DEFAULT_DTE*1.0)/365.0,riskvolused,'c');
			prices[1] = Math.floor(b.strike(-SHORT_DELTA));
			prices[3] = Math.floor(b.strike(SHORT_DELTA));
			double width = get_ic_min_width(prices[2], optvolused);
			prices[0] = prices[1] - width;
			prices[4] = prices[3] + width;
			m.init(initdt,prices[0],prices[1],prices[2],prices[3],prices[4]);
		}

		cnt = 0;
		for (int dt=initdt;dt <= Dates.today();dt=Dates.offset(dt,1))
		{
			m.tick(dt);
			if (volcsv == null)
			{
				riskvolused = RISKVOL_OFFSET+Math.max(m.feat.optvol(),m.feat.histvol())/100.0;
				m.setRiskVol(riskvolused);
			}
			if (log != null) log.add(m.log());
			//m.ManualAdjust();
			if (++cnt > DEFAULT_DTE) break;
		}
		System.out.println("On close:");
		double pnl = m.pnl(2);
		int trdcnt = 0;
		for (double[] trd:m.trades)
			trdcnt += (int)Math.abs(trd[2]);
		Context.log("Closing PNL",pnl,"Cost",0.65*trdcnt,"Price", m.icpx());
		return pnl - 0.65*trdcnt;
	}
}
