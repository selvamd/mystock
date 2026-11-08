package com.mystock.legacy;

import java.util.*;

//Using shared buffer concurrently in multiple timeseries objects is risky.
//Use (with due precautions in user code) if lot of objects has to be cached
public class TimeSeries
{
	protected final TimeSeriesData values; //This could be a sparsely populated object
	protected int fromidx; 	// range inclusive
	protected int toidx;   	// range exclusive

	protected double[] sortd; // cache needed for ntile funcs

	//Used for trends and corr
	protected double Exy;
	protected double Ex;
	protected double Ey;
	protected double Ex2;
	protected double Ey2;


    public static TimeSeries build(TreeMap<Integer,Double> dt2px) {
		return new TimeSeries(new TimeSeriesData(dt2px));
    }

    public static TimeSeries buildDR(TreeMap<Integer,Double> dt2px)
    {
 	   TreeMap<Integer,Double> res = new TreeMap<Integer,Double>();
 	   double px = 0;
 	   for (int dt:dt2px.keySet()) {
 	   	   double v = dt2px.get(dt);
 		   res.put(dt, (px == 0)? 100.0:v*100/px);
 		   px = v;
 	   }
 	   TimeSeries ts = new TimeSeries(new TimeSeriesData(res));
 	   return ts;
    }

    public static TimeSeries buildStdMktPx(TreeMap<Integer,Double> dt2px)
    {
 	   double px = dt2px.get(dt2px.lastKey());
 	   for (int dt:dt2px.keySet())
 	   {
 		   double val = (dt2px.get(dt) * 100) / px;
 		   dt2px.put(dt,val);
 	   }
 	   TimeSeries ts = new TimeSeries(new TimeSeriesData(dt2px));
 	   return ts;
    }

	//Inputs are metric values for corresponding monotonically increasing date array
	public TimeSeries(TimeSeriesData v) { this(v , new double[Dates.dates.length]); }

	//Pass dblarr explicitly if external storage needs to be used
	public TimeSeries(TimeSeriesData v, double[] dblarr)
	{
		values = v; sortd = dblarr;
		setWindow(Dates.dates[0],Dates.dates[Dates.dates.length-1]);
	}

	//Sets a time window within the timeseries where all calculations are performed.
	//Make sure intarr.length = dblarr.length = Dates.dates.length = values.length
	//Uses fromdt,todt(range inclusive) to deduce respective fromidx, toidx.
	//The corresponding calculated toidx is range exclusive
	public void setWindow(int fromdt, int todt)
	{
		fromidx = Dates.toindex(fromdt);
		toidx   = 1+Dates.toindex(todt);
		trend();
	}

	public void setWindow(int refdt, double val)
	{
		int idx = 0;
		double dmax = 0;
		double dmin = Double.MAX_VALUE;
		int refidx   = Dates.toindex(refdt);
		double range = this.ema(refdt,200) * val/100.0;
		for (idx = refidx;idx >= 0; idx--) {
			dmin = Math.min(dmin,values(idx));
			dmax = Math.max(dmax,values(idx));
			if (dmax - dmin > range) break;
		}
		fromidx = idx;
		toidx   = 1+refidx;
		//System.out.println("dmax " + dmax + " dmin " + dmin + " range " + range + " fromidx " + fromidx + " toidx " + toidx);
		trend();
	}

	public void setWindow(TimeSeries ts)
	{
		fromidx = ts.fromidx;
		toidx   = ts.toidx;
		trend();
	}

	public void print() { for (int i=0;i<toidx;i++) System.out.println(idt(i)+","+values(i)); }
	public void print(int fromidx, int toidx) { values.print(fromidx,toidx); }
	public int count() 			{ return toidx - fromidx; }

	public TimeSeriesData data() { return values; }

	public double asof(int dt)  { return values(Dates.toindex(dt)); }
	public double at(int idx)   { return values(idx); }

	public int idx(int dt) { return Dates.toindex(dt); }
	public int idt(int idx) { return Dates.todate(idx); }

	public double lookback(int days)  { return values(toidx-1-days); }

	public double first() 		{ return values(fromidx); }
	public double last()  	 	{ return values(toidx); }
	public double firstfew() 	{ return (count() <= 5)? mean():sum(fromidx,fromidx+5)/5.0; }
	public double lastfew()  	{ return (count() <= 5)? mean():sum(toidx-5,toidx)/5.0; }
	public double mean()     	{ return sum(fromidx, toidx)/count(); }


	public double md() 			{ return deviation(fromidx,toidx,1.0); }
	public double sd() 			{ return deviation(fromidx,toidx,2.0); }
	public double xd() 			{ return deviation(fromidx,toidx,4.0); }

	//Goodness of fit of the trend line
	public double r2() 			{ return 1.0 - (Math.pow(bumpiness()/sd(),2)); }

	public double zscore()      { return (last()-mean())/sd(); }

	public double maxuntil(int daysago) { int idx = findminmax(fromidx,toidx-daysago,false);  return values(idx); }

	//New minimums made during the lookback period
	public double minduring(int dayslookedback)
	{
		int idx = findminmax(fromidx,toidx-dayslookedback,false);
		int minidx1 = findminmax(idx,toidx-dayslookedback,true);
		int minidx2 = findminmax(idx,toidx,true);
		return values(minidx1) - values(minidx2);
	}

	//New maximums made during the lookback period
	public double maxduring(int dayslookedback)
	{
		int idx = findminmax(fromidx,toidx-dayslookedback,false);
		int idx2 = findminmax(fromidx,toidx,false);
		return values(idx2) - values(idx);
	}

	public double min() 		{ int idx = findminmax(fromidx,toidx,true);  return values(idx); }
	public double max() 		{ int idx = findminmax(fromidx,toidx,false); return values(idx); }

	public int mindt() 		    { int idx = findminmax(fromidx,toidx,true);  return Dates.dates[idx]; }
	public int maxdt() 		    { int idx = findminmax(fromidx,toidx,false); return Dates.dates[idx]; }

	public double momentum()	{ return (slope() * 200 / (yinit()+yend()));  } //PNL/day for $100 investment
	public double slope()    	{ return (count() * Exy - Ex * Ey) / (count() * Ex2 - Ex * Ex); } //change value per day
	public double yend()  		{ return yidx(toidx-1); } // last val in trendline
	public double yinit()  		{ return yidx(fromidx); } // first val in trendline
	public double yidx(int idx) { return ((Ey * Ex2 - Ex * Exy) / (count() * Ex2 - Ex * Ex)) + slope() * idx; } // first val in trendline

	public double sma(int dt, int n)	{ return movavg(dt,n,0.0); }
	public double lma(int dt, int n)	{ return movavg(dt,n,1.0); }
	public double ema(int dt, int n)	{ return movavg(dt,n,2.0); }

	public double median() 		{ return ntile(0.5); }

	public double ntile(double pct)
	{
		//Happens only if time window changed
		values.loadAndSort(sortd,fromidx,toidx);
		int idx = fromidx + (int)(count() * pct);
		return (count()%2==1)? sortd[idx]:(sortd[idx]+sortd[idx-1])/2.0;
	}

	public double maxgain()
	{
		int low = findminmax(fromidx,toidx,true);
		int high = findminmax(fromidx,toidx,false);
		if (low < high) return values(high) - values(low);
		int lowb4high = findminmax(fromidx,high,true);
		int highafter = findminmax(low,toidx,false);
		return Math.max(values(high) - values(lowb4high),values(highafter) - values(low));
	}

	public double maxloss()
	{
		int low = findminmax(fromidx,toidx,true);
		int high = findminmax(fromidx,toidx,false);
		if (low > high) return values(high) - values(low);
		int highb4low = findminmax(fromidx,low,false);
		int lowafter  = findminmax(high,toidx,true);
		return Math.max(values(highb4low) - values(low),values(high) - values(lowafter));
	}

	//Quantifies dispersion around trendline
	public double bumpiness()
	{
		double sumd = 0, slope = slope(), yinit = yinit();
		for (int i=fromidx; i < toidx; i++)
			sumd += Math.pow(values(i)-(yinit+slope*(i-fromidx)),2.0);
	    return Math.pow(sumd/count(),0.5);
	}

	public double cokurt(TimeSeries ts, double powx, double powy, int mktdir) {
		//Note that time window (fromdt,todt) for both timeseries (ts and this) need not be the same.
		//This is intentional as correlation can be applied with a lead/lag in timelines.
		//Only requirement is we have equal number of observations to correlate
		assert (count() == ts.count() && count() > 0);
		double Exy = 0, N = 0, avgx = mean(), avgy = ts.mean();
		for (int i = 0; i < count(); i++) {
			double mkt = ts.values(ts.fromidx+i) - avgy;
			double ast = values(fromidx+i) - avgx;
			if (mktdir < 0 && mkt >= 0) continue;
			if (mktdir > 0 && mkt <= 0) continue;
			Exy += Math.pow(ast,powx) * Math.pow(mkt,powy);
			N++;
		}
		return (N == 0)? 0:Exy/(N*Math.pow(sd(),powx)*Math.pow(ts.sd(),powy));

	}

    //Coskew = E[(x-Ex)*(y-Ey)*(z-Ez)]/(sigx*sigy*sigz) where y=z
	//Avg asset return after weighting with squared mkt return
	public double coskew(TimeSeries ts) {
		return cokurt(ts,1.0,2.0,0);
	}

	public double corr(TimeSeries ts)
	{
		//Note that time window (fromdt,todt) for both timeseries (ts and this) need not be the same.
		//This is intentional as correlation can be applied with a lead/lag in timelines.
		//Only requirement is we have equal number of observations to correlate
		assert (count() == ts.count() && count() > 0);
		double Exy = 0, N = count();
		for (int i = 0; i < count(); i++)
			Exy += ts.values(ts.fromidx+i) * values(fromidx+i);
		//System.out.println(ts.values(ts.fromidx) +":" + values(fromidx));
		return (N * Exy - ts.Ey * Ey)/Math.pow((N * ts.Ey2 - ts.Ey * ts.Ey) * (N * Ey2 - Ey * Ey),0.5);
	}

	///////////////////////////////////////// PRIVATE METHODS ////////////////////////
	private double values(int idx) {
		return values.get(idx);
	}

	//sma,lma,ema
	private double movavg(int dt, int depth, double degree)
	{
		assert(dt > Dates.dates[depth] && dt < Dates.dates[Dates.dates.length-1]);
		double sumpow = 0, res = 0;
		for (int i=0;i<depth;i++)
			sumpow += Math.pow(i,degree);

		int idx = Dates.toindex(dt);
		for (int i=0;i<depth;i++)
			res += (values(idx-depth+i)*Math.pow(i,degree));
		return res/sumpow;
	}

	private double sum(int from, int to)
	{
		double val = 0;
		for (int i=from;i<to;i++)
			val += values(i);
		return val;
	}

	//md,sd,xd
	private double deviation(int from, int to, double pow)
	{
		double mean = sum(from,to)/(to-from), res = 0;
		for (int i=from;i<to;i++)
			res += Math.pow(Math.abs(values(i)-mean),pow);
		return Math.pow(res/count(),1.0/pow);
	}

    private int findminmax(int from, int to, boolean ismin)
	{
		int idx = from;
		for (int i=from;i<=to;i++) {
			if (ismin && values(i) < values(idx)) idx = i;
			if ((!ismin) && values(i) > values(idx)) idx = i;
		}
		return idx;
    }

	private void trend()
	{
		Exy = Ex = Ey = Ex2 = Ey2 = 0;
		for (int i=fromidx;i<toidx;i++) {
			Exy += i * values(i);
			Ex2 += i * i;
			Ex  += i;
			Ey  += values(i);
			Ey2 += values(i)*values(i);
		}
	}

	//////////////////// Functions for Aggregating/NonDelta Metrics //////////////////////
	public double pctchg(int lookbackdays) { return (lookback(lookbackdays) == 0)? 0:100.0 * (last()-lookback(lookbackdays))/lookback(lookbackdays); }
	public double lastdevz()   {  return (sd()==0)? 0:(last() - yend()) / sd(); }
	public double rangescore() { return 100.0 * (last() - min()) / (max() - min()); }

	public double discount()   { return (100.0 * (max()-last())) / max(); }
	public double rebound()    { return (last()-min()); }
	public double cagr()       { return (lastfew() / firstfew() - 1.0) * 100.0 * (365.0 / (toidx - fromidx)); }
    //roi from buying at dlr cost avg px and selling today
	public double dca_roi()    { return (last() / mean() - 1.0) * 100.0; }

	// timestrength - Time for min/max values
	public double aroonup()    { return (toidx-findminmax(fromidx, toidx,  true)) * 100.0 / (toidx - fromidx); }
	public double aroondwn()   { return (toidx-findminmax(fromidx, toidx, false)) * 100.0 / (toidx - fromidx); }

    //alpha = (R - Rf) - beta * (Rm - Rf) - Rf (Rf = risk free return, R = stock return, Rm = Market return)
    public double alpharoi(TimeSeries tsref, double beta) { return cagr() - betaroi(tsref,beta); }
    public double betaroi(TimeSeries tsref, double beta) { return beta * tsref.cagr(); }

	//Indicates degree of seasonal variation
	public double seasonality() {
		double res = 0;
		for (int i=1;i<=12;i++)
			res += Math.abs(seasonindex(i))/12.0;
		return res;
	}

	//return season variation each month
	public double seasonindex(int mm)
	{
			double exp = 0, unexp = 0, cnt = 0;
			for (int i=fromidx;i < toidx; i++) {
					int dtmm = (Dates.todate(i)/100)%100;
					if (dtmm != mm) continue;
					if (at(i) == 0) continue;
					//exp   += slope();
					double var = at(i) - at(i-1) - slope();
					unexp += 100 * var / at(i);
					cnt += 1.0;
			}
			//System.out.println("sindex=" + 30.0*unexp/cnt);
			return (cnt == 0)? 0: 30.0*unexp/cnt;
			//double total = Math.abs(exp) + Math.abs(unexp);
			//return (total == 0)? 0:unexp/total;
	}

    //days elapsed since curr px level was first reached in last 2 years
    public int hangtime()
    {
 	   int idx = Math.max(0,Dates.dates.length - 730); //2yrs
 	   for (int i=idx;i<Dates.dates.length;i++)
 		   if (at(i) >= 98.0)
 			   return Dates.dates.length - i;
 	   return 0;
    }

	//Calculate curr optvol
	//min-max 30D forward
	//Check if spr is touched or breached
	public double tailriskbreach(double vol)
	{
		int res = 0, tdays = 45;
		if (vol == 0) vol = optvol();
		for (int i=0;i<100;i++) {
			int dtidx = toidx - tdays - i * 5;
			//double dev = deviation(dtidx-tdays,dtidx,2.0) * Math.sqrt(tdays) * 3.0;
			//double spr = at(dtidx) * optvol(dtidx) * Math.pow(tdays/365.0,0.5) * 0.01 * 3.0;
			BSOption b = new BSOption(at(dtidx),at(dtidx),45.0/365.0,vol/100.0,'p');
			double spr = Math.abs(at(dtidx) - b.strike(-0.15));
			//System.out.println(optvol()+","+Dates.todate(dtidx)+","+at(dtidx)+","+at(dtidx+tdays)+","+Math.abs(at(dtidx)-at(dtidx+tdays))+","+spr+","+res);
			if (Math.abs(at(dtidx)-at(dtidx+tdays)) >= spr)
				res++;
		}
		return res;
	}

	//Calculate curr optvol
	//min-max 30D forward
	//Check if spr is touched or breached
	public double tailrisktouch(double vol) {
		int res = 0, tdays = 45;
		if (vol == 0) vol = optvol();
		for (int i=0;i<100;i++) {
			int dtidx = toidx - tdays - i * 5;
		    int minidx = findminmax(dtidx,dtidx+tdays,true);
			int maxidx = findminmax(dtidx,dtidx+tdays,false);
			BSOption b = new BSOption(at(dtidx),at(dtidx),45.0/365.0,vol/100.0,'p');
			double spr = Math.abs(at(dtidx) - b.strike(-0.15));
			//double spr = at(dtidx) * optvol(dtidx) * Math.pow(tdays/365.0,0.5) * 0.01 * 3.0;
			//double dev = deviation(dtidx-tdays,dtidx,2.0) * Math.sqrt(tdays);
			//System.out.println(at(dtidx)+","+at(minidx)+","+at(maxidx)+","+dev+","+spr);
			if (Math.max(at(dtidx) - at(minidx), at(maxidx) - at(dtidx)) >= spr)
				res++;
		}
		return res;
	}

	//Count of 2 x 10d-vol breached in the last 100 days.
	public double tailrisk() {
		int res = 0, tdays = 10;
		double avgvol = 0;
		for (int i=0;i<100;i++)
			avgvol += optvol(i+tdays)/100.0;
		for (int i=0;i<100;i++) {
			double mind = 9999999.99, maxd = 0, last = 0;
			for (int j=0; j < tdays; j++ ) {
				last = at(toidx - i - j);
				mind = Math.min(mind,last);
				maxd = Math.max(maxd,last);
			}
			double spr = last * 0.01 * avgvol * Math.pow(tdays/365.0,0.5) * 2.0;
			if (maxd - last > spr || last - mind > spr)
				res++;
		}
		return res;
	}

	//measure of tail risk. Low vol, high tailrisk are bad
	//for credit strategies
	public double kurtosis() {
	    double avg = 0.0, var2 = 0.0, var4 = 0.0;

	    for (int i = 0; i < 90; ++i)
			avg += (at(toidx - i) - at(toidx - i - 1))/(90.0*at(toidx - i - 1));

	    for (int j = 0; j < 90; ++j) {
			double d = (at(toidx - j) - at(toidx - j - 1))/at(toidx - j - 1);
			var2 += Math.pow(d - avg, 2.0);
			var4 += Math.pow(d - avg, 4.0);
		}
	    return (90*var4/(var2*var2)) - 3.0;
	}

	public double optvol() {
		return optvol(0);
	}

	public double rangevol(int trailingdays) {
		//realized vol = sqrt(365/trailingdays)*ln(high/low)/1.596
		double vol = Math.log((double)at(findminmax(toidx-trailingdays,toidx,false))/
						(double)at(findminmax(toidx-trailingdays,toidx,true)));
		return 100.0 * Math.pow(365.0/trailingdays,0.5)*vol/1.596;
	}

	//Simlated implied vol (use lma of price moves to add recency bias)
	public double optivol(int trailingdays) {
	    double avg = 0.0, var = 0.0;
	    for (int i = 0; i < 22; ++i)
	        avg += (22-i) * Math.log(at(toidx - i - trailingdays) / at(toidx - i - trailingdays - 1)) / 210.0;
	    for (int j = 0; j < 22; ++j)
	        var += (22-j) * Math.pow(Math.log(at(toidx - trailingdays - j) / at(toidx - trailingdays - j - 1)) - avg, 2.0);
	    return Math.pow(252.0, 0.5) * Math.pow(var / 210.0, 0.5) * 100.0;
	}

	//vol calculated over longer period 
	public double deepvol(int trailingdays, int window) {
	    double avg = 0.0, var = 0.0;
	    for (int i = 0; i < window; ++i)
	        avg += Math.log(at(toidx - i - trailingdays) / at(toidx - i - trailingdays - 1)) / (window * 1.0);
	    for (int j = 0; j < window; ++j)
	        var += Math.pow(Math.log(at(toidx - trailingdays - j) / at(toidx - trailingdays - j - 1)) - avg, 2.0);
	    return Math.pow(252.0, 0.5) * Math.pow(var / (window - 2.0), 0.5) * 100.0;
	}

	public double optvol(int trailingdays) {
		return deepvol(trailingdays,22);
	    // double avg = 0.0, var = 0.0;
	    // for (int i = 0; i < 22; ++i)
	    //     avg += Math.log(at(toidx - i - trailingdays) / at(toidx - i - trailingdays - 1)) / 21.0;
	    // for (int j = 0; j < 22; ++j)
	    //     var += Math.pow(Math.log(at(toidx - trailingdays - j) / at(toidx - trailingdays - j - 1)) - avg, 2.0);
	    // return Math.pow(252.0, 0.5) * Math.pow(var / 20.0, 0.5) * 100.0;
	}

	// public double longvol(int trailingdays) {
	//     double tr = 0.0, trmin = 100.0, trmax = 0.0;
	//     for (int i = 0; i < 49; ++i) {
	// 		tr += Math.abs(at(toidx - i - trailingdays) - at(toidx - i - trailingdays - 1)) / 49.0;
	// 		trmin = Math.min(trmin,at(toidx - i - trailingdays));
	// 		trmax = Math.max(trmax,at(toidx - i - trailingdays));
	// 	}
	// 	return (trmax-trmin) * optvol(trailingdays) / (7.0 * tr);
	// }

	public double rsi() {
	    double n = 0.0, n2 = 1.0E-6;
	    for (int i = 0; i < 14; ++i) {
	        n += Math.max(at(toidx - i) - at(toidx - i - 1), 0.0);
	        n2 += Math.max(at(toidx - i - 1) - at(toidx - i), 0.0);
	    }
	    return 100.0 - 100.0 / (1.0 + n / n2);
	}

	public double max_drs_ttm() {
		//monthly max dr for tailing year (applied on dr timeseries)
		double res = 1;
		for (int i=0;i<12;i++) {
			double dip = -0.00001;
			for (int j=0;j<30;j++)
				dip = Math.max(dip,at(toidx - j - 30 * i)-100);
			dip = 1+Math.abs(Math.floor(dip));
			if (dip >= 10) dip = 0;
			res = res*10+dip;
		}
		return res;
	}

	public double min_drs_ttm() {
		//monthly min dr for tailing year (applied on dr timeseries)
		double res = 1;
		for (int i=0;i<12;i++) {
			double dip = -0.00001;
			for (int j=0;j<30;j++)
				dip = Math.min(dip,at(toidx - j - 30 * i)-100);
			dip = Math.abs(Math.floor(dip));
			if (dip >= 10) dip = 0;
			res = res*10+dip;
		}
		return res;
	}
	//////////////////// Functions for Delta Metrics //////////////////////
	//Tells how jerky the market movement is in last 100 days
	public double sharpness() {
		double res = 0;
		for (int i = toidx-100;i < toidx; i++)
			res += Math.pow(100.0-at(i),4);
		return Math.pow(res/100,0.25);
	}

	//Find optimal consecutive days of trailing pctchg that best predicts next day direction
	//If for given days-window, there are not enough samples (<10%), donot use it
	//Not used (Initials results only shows a small bias towards reversing direction)
	public int stickytrend() {
		int spottrend = 0, score = 0;
		for (int i = 0;i <= 7; i++) {
			int[] res = stickytrendscore(i+1);
			if (res[2] < 10) continue;
			int s = (100 * res[1])/res[2];
			//System.out.println((i+1)+","+res[0]+","+res[1]+","+res[2]);
			if (s < score) continue;
			score = s;
			spottrend = res[0];
		}
		if (spottrend == 0) return 0;
		return (at(toidx) > at(toidx-1))? score:-1*score;
	}

	private int[] stickytrendscore(int days) {
		int[] res = new int[3];
		for (int i=0;i<100;i++) {
			if (!checktrend(toidx-i-1,days)) continue;
			if (checktrend(toidx-i,days+1)) res[1]++;
			res[2]++;
		}
		if (checktrend(toidx,days))
			res[0]++;
		return res;
	}

	private boolean checktrend(int start, int days) {
		Set<Double> chkset = new HashSet<>();
		for (int idays=0;idays < days+5;idays++) {
			chkset.add(at(start-idays));
			if (chkset.size() == days) break;
		}
		//System.out.println(chkset);
		double d1 = Math.abs(chkset.stream().mapToDouble(d-> d-100.0).sum());
		double d2 = chkset.stream().mapToDouble(d-> Math.abs(d-100.0)).sum();
		return (d1 == d2);
	}


    //beta = market corr * (ratio of variances)
    public double beta(TimeSeries drref) {  return corr(drref) * (sd()/drref.sd()); }

    //2% downticks lastmonth
    public double downtickspermonth(double limit) { return downticks(limit,30); }

	public double downticks(double limit, int spandays) {
 	   double count = 0.0;
 	   for (int i=0;i<=spandays;i++)
 		   if (lookback(i) <= (100.0-limit))
 			   count++;
 	   return count;
    }

    //How crash resistant a stock is? downcorr when mkt tumbles.
    public double dcorr(TimeSeries refdr, Boolean upticks) {
        //Correlation = (NExy-Ex*Ey)/sqrt((NEx2-ExEx)*(NEy2-EyEy))
 	   double N = 0, Ex = 0, Ey = 0, Exy = 0, Ex2 = 0, Ey2 = 0;
 	   for (int i = 0; i < 365; i++) {
 		   double x = refdr.lookback(i);
 		   double y = lookback(i);
 		   if (upticks != null && upticks.booleanValue() && x < 100) continue;
 		   if (upticks != null && (!upticks.booleanValue()) && x > 100) continue;
 		   N += 1.0;Ex += x; Ey += y;
 	   }
 	   Ex /= N; Ey /= N;
 	   for (int i = 0; i < 365; i++)
 	   {
 		   double x = refdr.lookback(i);
 		   double y = lookback(i);
 		   if (upticks != null && upticks.booleanValue() && x < 100) continue;
 		   if (upticks != null && (!upticks.booleanValue()) && x > 100) continue;
 		   Exy += (x - Ex)*(y - Ey);
 		   Ex2 += (x-Ex) * (x-Ex);
 		   Ey2 += (y-Ey) * (y-Ey);
 	   }
 	   return Exy/(Math.sqrt(Ex2)*Math.sqrt(Ey2));
    }

	public static void main(String[] args)
	{
		Dates.init(20180101,20210101);
		double[] px = new double[Dates.dates.length];
		for (int i=0;i<Dates.dates.length;i++)
			px[i] = 100.0 + (i * 0.10);
		TimeSeriesData data = new TimeSeriesData(Dates.dates,px);
		TimeSeries ts = new TimeSeries(data);
		ts.setWindow(20180601,20190601);
		System.out.println(ts.min());
		System.out.println(ts.max());
		System.out.println(ts.mindt());
		System.out.println(ts.maxdt());
		System.out.println(ts.aroonup());
		System.out.println(ts.aroondwn());
	}

}
