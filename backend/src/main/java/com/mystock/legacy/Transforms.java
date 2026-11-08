package com.mystock.legacy;

import java.util.*;

//Transformation functions to manipulate timeseries
//All results are returned as TimeseriesData
public class Transforms
{
	public static interface TsFunction {
		//Applies arithmentic transformation
		public double apply(double d1, double d2);
	}

	public static interface WinFunction {
		//Applies moving window transformation to timeseries
		public double apply(TimeSeries[] ts, int w);
	}

	public static final WinFunction PCTCHG = new WinFunction() { public double apply(TimeSeries[] ts, int w) { return ts[0].pctchg(w); } };
	public static final WinFunction OPTVOL = new WinFunction() { public double apply(TimeSeries[] ts, int w) { return ts[0].optvol(); } };

	public static final TsFunction ADD = new TsFunction() { public double apply(double d1, double d2) { return d1+d2; } };
	public static final TsFunction SUB = new TsFunction() { public double apply(double d1, double d2) { return d1-d2; } };
	public static final TsFunction MUL = new TsFunction() { public double apply(double d1, double d2) { return d1*d2; } };
	public static final TsFunction DIV = new TsFunction() { public double apply(double d1, double d2) { return d1/d2; } };

	//Perform sliding window calculations
	public static TimeSeriesData compute(TimeSeries[] tss, int days, WinFunction func)
	{
		TreeMap<Integer,Double> res = new TreeMap<Integer,Double>();
		for (int i=0;i<Dates.dates.length-days;i++)
		{
			for (TimeSeries t:tss)
				t.setWindow(Dates.dates[i],Dates.dates[i+days]);
			res.put(Dates.dates[i+days], func.apply(tss,days));
		}
	  	return new TimeSeriesData(res);
	}

	public static TimeSeriesData compute(TimeSeriesData[] ts, int days, WinFunction func)
	{
		TimeSeries[] tss = new TimeSeries[ts.length];
		for (int i=0;i<ts.length;i++)
			tss[i] = new TimeSeries(ts[i]);
		return compute(tss,days,func);
	}

	//Perform timeseries aggregation
	public static TimeSeriesData compute(TimeSeriesData ts1, TimeSeriesData ts2, TsFunction func)
	{
		double[] d = new double[Dates.dates.length];
		for (int idx=0;idx < Dates.dates.length; idx++)
			d[idx] = func.apply(ts1.get(idx),ts2.get(idx));
		return new TimeSeriesData(Dates.dates,d);
	}

	//Perform arithmetic
	public static TimeSeriesData compute(TimeSeriesData ts1, double c, TsFunction func)
	{
		double[] d = new double[Dates.dates.length];
		for (int idx=0;idx < Dates.dates.length; idx++)
			d[idx] = func.apply(ts1.get(idx),c);
		return new TimeSeriesData(Dates.dates,d);
	}

	public static void main(String[] args)
	{
		Dates.init(20180101,20210101);
		double[] px = new double[Dates.dates.length];
		for (int i=0;i<Dates.dates.length;i++)
			px[i] = 100.0 + (i * 0.10);
		TimeSeriesData data1 = new TimeSeriesData(Dates.dates,px);
		TimeSeriesData data2 = new TimeSeriesData(Dates.dates,px);
		//TimeSeries ts = new TimeSeries(data);
		//ts.setWindow(20180601,20190601);
		TimeSeriesData data3 = compute(data1,data2,SUB);
		TimeSeries ts = new TimeSeries(data3);
		System.out.println(ts.min());
		System.out.println(ts.max());
	}

}
