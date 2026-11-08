package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;
import com.google.gson.stream.JsonReader;
import java.text.DecimalFormat;

//Provides TimeSeries based features for ML model
public class Const
{
	public static DecimalFormat df = new DecimalFormat("#.##");
	public static final String dimkeys = "ticker,Name,Sector,Industry,MktSize";
	public static final String[] dimkeysarr = "ticker,name,sect,ind,mktsize".split(",");
	public static final String MKTETF = "RSP"; //replaced SPY with RSP 
	public static String path = new String[] {"/data/",
		"/Users/selvamdoraisamy/local/Desktop/github/mystk/data/"}[0];
	public static int MIN_DT = 20211231;//20201231
	public static int REBOUND_DT = 20220601;//20211231

	public static int NEXT_EXPIRY_DT = 20231215;
	static {
		// CHANGED (boundary): legacy hard-coded the 2024 monthly expiries (expr24); the list is now
		// generated dynamically (3rd Friday of each month, from 3 months back to 15 months ahead).
		// Selection logic below is unchanged: latest monthly expiry less than 45 days out.
		int[] exprs = monthlyExpiries();
		for (int dt:exprs)
			if (Dates.dateToDays(dt) < 45)
				NEXT_EXPIRY_DT = dt;
			else break;
	}

	// ADDED: monthly option expiries (3rd Friday) as yyyymmdd, ascending.
	public static int[] monthlyExpiries() {
		java.time.LocalDate first = java.time.LocalDate.now().withDayOfMonth(1).minusMonths(3);
		int[] res = new int[18];
		for (int i = 0; i < res.length; i++) {
			java.time.LocalDate m = first.plusMonths(i);
			java.time.LocalDate f = m.with(java.time.temporal.TemporalAdjusters.dayOfWeekInMonth(3, java.time.DayOfWeek.FRIDAY));
			res[i] = f.getYear() * 10000 + f.getMonthValue() * 100 + f.getDayOfMonth();
		}
		return res;
	}

	public static final double PR_SCORE = 33.0;
	public static int INIT_DT = 20150101;

	//SMH,GDX,GDXJ,XBI,XRT,XHB,EWW,IWM,EWZ,XME
	public static final String etf = 
		"SPY,RUT,DIA,GDX,GDXJ,GLD,SLV,USO,TLT,UNG,CPER,BNO,BITO,BST," + 
		"WEAT,SOYB,XSD,QQQ,ARKK,XLV,XLF,XLP,SCHG,SCHD,XLU,URA,CORN," + 
		"SCHV,XLY,XLB,XLI,XLE,XOP,FXE,XLC,XLK,XLRE,VNQ,VXX,VXY,IWM," + 
		"SMIN,EPI,VGK,FEZ,DXJ,HEWJ,RJA,RJI,RSP,RSPT,INDA,UUP,GRNY,BRK.B," + 
		"KRE,KBE,FXI,EEM,KWEB,UVXY,SMH,XBI,XRT,XHB,EWW,IWM,EWZ,XME," + 
		"NFTY,INDY,EWJ,EWT,EWG,EWY,EWC,EWA,EWU,EWS,EWH,ICLN,TAN,IJR,QVMS,SQLT," + 
		"FXF,ICLN,TAN,CIBR,BUG,ITA,XAR,VBR,SLYG,SCHA,CALF,TOPT,IGV,AVUV,DFAS";
		
	private static String format(int d,boolean up) {
		String res = " ";
		for (int i=0;i<d;i++) {
			if (i%7==0) res += ' ';
			if (up) res += 'u'; //'↑'
			else res += 'd'; //'↓'
		}
		return res + " ";
	}

	public static String summ(TimeSeries ts) {
		ts.setWindow(Dates.dates[Dates.dates.length-31],Dates.dates[Dates.dates.length-1]);
		String res = Const.df.format(ts.lookback(30));
		int dt = 0;
		if (ts.mindt() < ts.maxdt()) {
			dt = Dates.toindex(ts.mindt()) - (Dates.dates.length-31);
			res += format(dt,false) + Const.df.format(ts.min());
			dt = Dates.toindex(ts.maxdt()) - Dates.toindex(ts.mindt());
			res += format(dt,true) + Const.df.format(ts.max());
			dt =  Dates.dates.length - Dates.toindex(ts.maxdt()) - 1;
			res += format(dt,false) + "100.00";
		} else {
			dt = Dates.toindex(ts.maxdt()) - (Dates.dates.length-31);
			res += format(dt,true) + Const.df.format(ts.max());
			dt = Dates.toindex(ts.mindt()) - Dates.toindex(ts.maxdt());
			res += format(dt,false) + Const.df.format(ts.min());
			dt =  Dates.dates.length - Dates.toindex(ts.mindt()) - 1;
			res += format(dt,true) + "100.00";
		}
		return res;
	}

	public static int intvalue(String str, int scale) {
		double d = Double.parseDouble(str);
		d *= scale;
		return (int)d;
	}
 
	public static void main(String[] args) throws Exception
	{
		String[] arr0 = etf.split(",");
		//String[] arr1 = etf_desc.split(",");
		//Context.log("etfs", arr0.length,"desc",arr1.length);
	}

}
