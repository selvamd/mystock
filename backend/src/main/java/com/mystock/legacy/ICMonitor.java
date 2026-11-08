package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;

public class ICMonitor
{
	private double[] strikes;
	private double upx;
	private double vol;
	private int expr;

	public ICMonitor(double[] s, double spx, double svol, int expr)
	{
		this.strikes = s;
		this.upx     = spx;
		this.vol     = svol;
		this.expr    = expr;
	}

	public double icpx() { return icpx(upx,vol,expr); }
	public double icpx(double upx,double vol,int idte) {
        double dte = idte/365.0;
		BSOption[] b = new BSOption[4];
		b[0] = new BSOption(upx,strikes[0],dte,vol,'p');
        b[1] = new BSOption(upx,strikes[1],dte,vol,'p');
        b[2] = new BSOption(upx,strikes[2],dte,vol,'c');
        b[3] = new BSOption(upx,strikes[3],dte,vol,'c');
        double res = 0;
		for (int i=0;i<b.length;i++) {
			if (i == 0 || i == 3) res += b[i].computePrice();
			else res -= b[i].computePrice();
		}
        return res;
    }

	public double icgreek(int type) { return icgreek(type,upx,vol,expr); }
	public double icgreek(int type, double upx,double vol,int idte)
	{
        double dte = idte/365.0;
		BSOption[] b = new BSOption[4];
        b[0] = new BSOption(upx,strikes[0],dte,vol,'p');
        b[1] = new BSOption(upx,strikes[1],dte,vol,'p');
        b[2] = new BSOption(upx,strikes[2],dte,vol,'c');
        b[3] = new BSOption(upx,strikes[3],dte,vol,'c');
		double res = 0;
		for (int i=0;i<b.length;i++) {
			double[] d = b[i].computeGreeks();
			if (i == 0 || i == 3) res += d[type];
			else res -= d[type];
		}
		return res;
    }

	public double[] icthresh(double initpx)
	{
		double[] res = new double[expr*3];
        for (int j=0;j<expr;j++) {
	        double minpx = 0, maxpx = 0;
	        int dte = j;
	        int range = (int)((strikes[3] - strikes[2])*2.0);
	        double midpx = (strikes[3]-range);
	        for (int i=0;i<range;i++) {
	            double px = midpx + i;
	            if (initpx - icpx(px,vol,dte) < 0)
	                 continue;
	            maxpx = px;
	            break;
	        }
	        range = (int)((strikes[1] - strikes[0])*2.0);
	        midpx = (strikes[0]+range);
	        for (int i=0;i<range;i++) {
	            double px = midpx - i;
	            if (initpx - icpx(px,vol,dte) < 0)
	                 continue;
	            minpx = px;
	            break;
	        }
			res[j*3] = dte;
			res[j*3+1] = minpx;
			res[j*3+2] = maxpx;
    	}
		return res;
	}

	public void print(double initpx) {
		double[] res = icthresh(initpx);
		for (int i=0;i < expr;i++)
			System.out.println(res[i*3]+","+res[i*3+1]+","+res[i*3+2]);
		System.out.println(icgreek(BSOption.DELTA));
	}
	//Criteria for IC:
	//  abs(mompnl) < 3 and abs(rangescore-50) < 10 and abs(prscore-50) < 10 and (optivol - optvol) < 5 and abs(histvol - optvol) < 5 and mktcap > 10 and xadv < 1 and volratio > 0.8

	public static void main(String[] args) throws Exception
	{
		Const.path = "./data/";
		Context.init();
        //upx,stk,expr,rate,yld,vol,opx,c/p
		String strstks = "SPY,QQQ,TSLA,IWM,NFLX,HYG,AMZN,AAPL,GOOGL,XLE,META,AMD,GOOG,NVDA,MSFT,GLD,BABA,TLT,EFA,PFE,XOM,DIS,JPM,PYPL,LQD,OXY,SQ,SBUX,ATVI,KRE,KO,JD,BA,GE,XBI,MS,DIA,QCOM,USO,SQQQ,XOP,PANW,SLB,ABNB,CVX,MRK,NKE,WMT,BMY,CRM,ZM,IBM,ORCL,XLU,DVN,XLK,EMB,MRNA,XLV,JNJ,V,NEE,FSLR,XLP,PG,GILD,XLI,MET,SE,ABBV,SMH,TGT,DOW,TMUS,COP,IYR,WYNN,AMAT,HD,VGK,IEF,GS,CVS,BX,SCHW,CAT,AEM,SNOW,CRWD,UPS,ADBE,BIDU,TXN,FXI,EEM,KWEB";
		String[] stks = strstks.split(",");
		System.out.println("ticker,upx,vol,s0,s1,s2,s3,opx,trb,trt");
		for (String stk:stks) {
			//Obtain the real price and implied vol
			double[] d = PVCache.register(stk);
	        double upx = d[0]; double vol = d[1]/100.0;int dte = 45;
			BSOption b = new BSOption(upx,upx,dte/365.0,vol,'c');
			double trt = 100, trb = 100;
			try {
				StockFeature f = new StockFeature(stk);
				trt = f.trisktouch();
				trb = f.triskbreach();
			} catch (Exception e) {
			}
			//Obtain the 15D spread and wings (5W,10W,15W) on both sides
			double s0 = Math.floor(b.strike(-0.05));
			double s1 = Math.floor(b.strike(-0.15));
			double s2 = Math.floor(b.strike(0.15));
			if (s1 - s0 > 10) s0 = s1 - 15;
			else if (s1 - s0 > 5) s0 = s1 - 10;
			else s0 = s1 - 5;
			//double s3 = Math.floor(b.strike(0.05));
			double s3 = s2+(s1-s0);
			double[] strikes  = new double[] {s0,s1,s2,s3};
			ICMonitor m = new ICMonitor(strikes,upx,vol,dte);
			System.out.println(stk + "," + upx + "," + String.format("%.2f", vol*100) + "," +
								s0 + "," + s1 + "," + s2 + "," + s3 + "," +
								String.format("%.2f", m.icpx())+","+trb+","+trt);
			Thread.sleep(1000);
		}

        // upx = 180.0; vol = 0.55;dte = 45;
		// m = new ICMonitor(strikes,upx,vol,dte);
		// m.print(-1.85);

		// Context.path = "./docker/data/";
		// Context.init();
		// StockInfo.init();
		// StockInfo info = new StockInfo("NVDA");
		//
		// System.out.println(info.ts.sma(Context.lastdt,30));
		// System.out.println(info.getd("trisktouch"));
		// System.out.println(info.getd("triskbreach"));
	}
}
