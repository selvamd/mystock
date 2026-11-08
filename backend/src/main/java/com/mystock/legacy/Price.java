package com.mystock.legacy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import java.nio.file.Files;
import java.io.File;

//Source for all timeseries related to single stock
public class Price {

    private final String ticker;
    private final TreeMap<Integer,int[]> prices;
    private int firstdt = 0;
    private int lastdt = 0;
    private TreeMap<Integer,Double> dt2px;

    public int firstdt() { return firstdt; }
    public int lastdt() { return lastdt; }

    public Price(String sym) {
        ticker = sym;
        prices = new TreeMap<Integer,int[]>();
        dt2px = new TreeMap<Integer,Double>();
    }

    private static final int adjclose = 0;
    private static final int open = 1;
    private static final int high = 2;
    private static final int low = 3;
    private static final int close = 4;
    private static final int prev = 5;
    private static final int adv = 6;

    public void process(ResultSet rs, String[] cols)
    throws Exception
    {
        int[] res = new int[cols.length];
        for (int i=0;i<cols.length;i++)
            res[i] = rs.getInt(cols[i]);
        if (res[prev] == 0) res[prev] = res[adjclose];
        if (firstdt == 0) firstdt = rs.getInt("asof");
        lastdt = rs.getInt("asof");
        prices.put(rs.getInt("asof"),res);
    }

    public TreeMap<Integer,Double> stdadv(int cutoffdt)
    {
        dt2px.clear();

        double lastval = 0;
        for (Integer dt:prices.keySet())
            if (cutoffdt == 0 || cutoffdt > dt.intValue())
                lastval = prices.get(dt)[adv];

        for (Integer dt:prices.keySet()) {
            if (cutoffdt != 0 && cutoffdt < dt.intValue())
                continue;
            int[] v = prices.get(dt);
            dt2px.put(dt,v[adv]*100.0/lastval);
        }
        return dt2px;
    }

    public TreeMap<Integer,Double> eoddr(int cutoffdt)
    {
        dt2px.clear();
        for (Integer dt:prices.keySet()) {
            if (cutoffdt != 0 && cutoffdt < dt.intValue())
                continue;
            int[] v = prices.get(dt);
            dt2px.put(dt,v[adjclose]*100.0/v[prev]);
        }
        return dt2px;
    }

    public TreeMap<Integer,Double> eodpctchg(int cutoffdt)
    {
        dt2px.clear();
        for (Integer dt:prices.keySet()) {
            if (cutoffdt != 0 && cutoffdt < dt.intValue())
                continue;
            int[] v = prices.get(dt);
            dt2px.put(dt,(v[adjclose]-v[prev])*100.0/v[prev]);
        }
        return dt2px;
    }

    public TreeMap<Integer,Double> atr(int cutoffdt)
    {
        dt2px.clear();

        double lastpx = 0;
        for (Integer dt:prices.keySet())
            if (cutoffdt == 0 || cutoffdt > dt.intValue())
                lastpx = prices.get(dt)[adjclose];

        for (Integer dt:prices.keySet()) {
            if (cutoffdt != 0 && cutoffdt < dt.intValue())
                continue;
            int[] v = prices.get(dt);
            double l = Math.min(v[prev],v[low]);
            double h = Math.max(v[prev],v[high]);
            //Context.log(dt,v[prev],v[low],v[high],v[adjclose],v[close],h,l,h-l);
            dt2px.put(dt,(h-l)*100.0/v[adjclose]);
        }
        return dt2px;
    }

    public TreeMap<Integer,Double> pxbytype(int cutoffdt, int type)
    {
        dt2px.clear();
        for (Integer dt:prices.keySet()) {
            if (cutoffdt != 0 && cutoffdt < dt.intValue())
                continue;
            int[] v = prices.get(dt);
            dt2px.put(dt,v[type]/100.0);
        }
        return dt2px;
    }

    public TreeMap<Integer,Double> lowpx(int cutoffdt) {
        return pxbytype(cutoffdt, low);
    }

    public TreeMap<Integer,Double> highpx(int cutoffdt) {
        return pxbytype(cutoffdt, high);
    }

    public TreeMap<Integer,Double> eodpx(int cutoffdt) {
        return pxbytype(cutoffdt, adjclose);
    }

    public TreeMap<Integer,Double> midpx(int cutoffdt) {
        dt2px.clear();
        for (Integer dt:prices.keySet()) {
            if (cutoffdt != 0 && cutoffdt < dt.intValue())
                continue;
            int[] v = prices.get(dt);
            dt2px.put(dt,(v[adjclose]+v[low]+v[high])/300.0);
        }
        return dt2px;
    }

    public TreeMap<Integer,Double> stdpx(int cutoffdt)
    {
        dt2px.clear();

        double lastpx = 0;
        for (Integer dt:prices.keySet())
            if (cutoffdt == 0 || cutoffdt > dt.intValue())
                lastpx = prices.get(dt)[adjclose];

        for (Integer dt:prices.keySet()) {
            if (cutoffdt != 0 && cutoffdt < dt.intValue())
                continue;
            int[] v = prices.get(dt);
            dt2px.put(dt,v[adjclose]*100.0/lastpx);
        }
        return dt2px;
    }

}
