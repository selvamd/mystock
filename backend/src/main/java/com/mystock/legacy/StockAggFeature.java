package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.function.*;
import com.google.gson.stream.JsonReader;

import java.text.DecimalFormat;
import java.util.stream.Collectors;

//Provides TimeSeries for a portfolio
public class StockAggFeature
{
	Map<String,StockFeature> agg = new HashMap<String,StockFeature>();

	public StockAggFeature() throws Exception {
		StockFeature spy = new StockFeature(Const.MKTETF);
		for (String stk: Context.symbols.keySet()) {
			try {
				StockFeature feat = new StockFeature(stk);
				feat.ref(spy);
				agg.put(stk, feat);
			} catch (Exception e) {
				System.out.println("Error loading "+ stk);
			}
		}
	}

	//timeseries of a particular percentile of the population
	public Map<Integer,Double> ntile_ts(double pct, Function<StockFeature,Double> fun) {
		Map<Integer,Double> res = new TreeMap<Integer,Double>();
		//We need lookback window of 365 for most metrics
		for (int idx = Dates.dates.length-1000; idx < Dates.dates.length; idx += 40)
		{
			List<Double> alist = xsect(Dates.dates[idx], fun);
			int lidx = (int)(pct*0.01*alist.size());
			if (alist.size() > 0)
				res.put(Dates.dates[idx],alist.get(lidx));
			System.out.println(Dates.dates[idx]+","+alist.get(lidx));
		}
		return res;
	}

	//gets a cross section of the population for a single point in time
	public List<Double> xsect(int idt, Function<StockFeature,Double> fun)
	{
		//TreeMap<Long, String> mapwithSortedKeys = stream
		//    .collect(Collectors.toMap(Item::id, Item::name, (oldv, newv) -> n, TreeMap::new));
		//List<Double> values = agg.values().stream().map( fun ).collect(Collectors.toList());
		List<Double> res = new ArrayList<Double>();
		for (StockFeature feat: agg.values()) {
			try {
				feat.set(idt);
				res.add(fun.apply(feat));
			} catch (RuntimeException e) {
			}
		}
		Collections.sort(res);
		return res;
	}

	public static void main(String[] args) throws Exception
    {
		//Const.path = "./data/";
		Context.init();
		StockAggFeature f = new StockAggFeature();
		System.out.println("Aggfeature done");
		System.out.println(f.ntile_ts(66,StockFeature::ema20));
		// try {
		// 	System.out.println(f.ntile_series(50,StockFeature::optvol));
		// } catch (Exception e) {
		// 	e.printStackTrace();
		// }
		// System.out.println(f.ts.tailriskbreach(vol));
		// System.out.println(f.optvol());
		// System.out.println(f.histvol());
	}

}
