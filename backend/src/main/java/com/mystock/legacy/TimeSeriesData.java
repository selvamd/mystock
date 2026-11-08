package com.mystock.legacy;

import java.util.*;

//Provides dense api to data in sparse format
public class TimeSeriesData
{
	private int[]    dtidx;
	private double[] values;

	public TimeSeriesData(Map<Integer,Double> dt2val) {
		values = new double[dt2val.size()];
		dtidx = new int[dt2val.size()];
		int idx = 0;
		for (int dt:dt2val.keySet()) {
			values[idx]  = dt2val.get(dt);
			//System.out.println(dt+"="+values[idx]);
			dtidx[idx++] = Dates.toindex(dt);
		}
	}

	public TimeSeriesData(int[] dt, double[] v)
	{
		assert(dt.length == v.length);

		//calculate size
		int size = 0;
		for (int i=0; i < v.length; i++)
			if (i == 0 || v[i-1] != v[i])
				size++;
		values = new double[size];
		dtidx  = new int[size];

		//populate changes
		size = 0;
		for (int i=0;i<dt.length;i++) {
			if (i == 0 || v[i-1] != v[i])
			{
				dtidx[size] = Dates.toindex(dt[i]);
				values[size++] = v[i];
			}
		}
	}

	public TimeSeriesData() {
		values = new double[] {0};
		dtidx = new int[] {0};
	}

	//Applies a lag. For previous close we can use lag=1
	public TreeMap<Integer,Double> load(TreeMap<Integer,Double> res, int lag) {
		res.clear();
		for (int i=0; i + lag < dtidx.length; i++)
			res.put(Dates.dates[dtidx[i+lag]],values[i]);
		return res;
	}

	public TreeMap<Integer,Double> load(TreeMap<Integer,Double> res) {
			return load(res,0);
	}

	public void print() { print(0,dtidx.length); }
    public void print(int fromidx, int toidx) {
		for (int i=fromidx; i < toidx; i++)
			System.out.println(Dates.dates[dtidx[i]]+","+values[i]);
    }

	public double last() { return values[values.length-1];}

	private int lasttoidx = 0;
	private int lastfromidx = 0;
	//Sort is costly and is skipped unless window changes
	protected void loadAndSort(double[] d, int fromidx, int toidx) {
		if (fromidx == lastfromidx && toidx == lasttoidx) return;
		for (int i=fromidx; i < toidx; i++) d[i] = get(i);
		Arrays.sort(d, fromidx, toidx);
	}

	public double get(int idx)
	{
		int v = Arrays.binarySearch(dtidx,idx);
		if (v >= 0) return values[v]; //exact match
		//return next lower value unless idx is before first entry
		return (v == -1)? values[0]:values[-2-v];
	}

	public static void main(String[] args) {
		Dates.init(20150101,20211231);
		TimeSeriesData d = new TimeSeriesData(new int[] {20170201,20180201},new double[] {10.0,20.0});
		for(int dt=0;dt <= 10; dt++)
			System.out.println((dt*10000+20130101) + "," + d.get(Dates.toindex(dt*10000+20130101)));
	}
}
