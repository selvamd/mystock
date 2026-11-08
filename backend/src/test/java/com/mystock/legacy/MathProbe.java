package com.mystock.legacy;

// Identical body to the probe compiled against the ORIGINAL legacy classes to produce golden/mathprobe.txt.
import java.util.*;

// Numeric probe of the DB-free legacy math classes. The same body is used (with a package line) by the
// JUnit test against com.mystock.legacy copies; outputs must match exactly (Double.toString).
public class MathProbe {
    public static List<String> run() {
        List<String> o = new ArrayList<>();
        Dates.init(20180101, 20211231);
        TreeMap<Integer, Double> px = new TreeMap<>();
        int i = 0;
        for (int dt = 20180101; dt <= 20211231; dt = Dates.offset(dt, 1), i++) {
            java.time.DayOfWeek w = java.time.LocalDate.of(dt / 10000, (dt / 100) % 100, dt % 100).getDayOfWeek();
            if (w == java.time.DayOfWeek.SATURDAY || w == java.time.DayOfWeek.SUNDAY) continue;
            px.put(dt, 100 + 10 * Math.sin(i / 20.0) + i * 0.05 + ((i * 7919) % 13) / 10.0);
        }
        TimeSeries dr = TimeSeries.buildDR(new TreeMap<>(px));
        TimeSeries raw = TimeSeries.build(new TreeMap<>(px));
        TimeSeries ts = TimeSeries.buildStdMktPx(new TreeMap<>(px));
        o.add("dates.len=" + Dates.dates.length);
        o.add("dates.toindex=" + Dates.toindex(20200229) + "," + Dates.toindex(20100101) + "," + Dates.toindex(20300101));
        o.add("dates.offset=" + Dates.offset(20200228, 1) + "," + Dates.offset(20210301, -1));
        int[][] wins = {{20200101, 20201231}, {20210101, 20211231}, {20190601, 20190901}};
        for (int[] w : wins) {
            ts.setWindow(w[0], w[1]); dr.setWindow(ts); raw.setWindow(ts);
            String p = "w" + w[0] + ".";
            o.add(p + "first,last,mean=" + ts.first() + "," + ts.last() + "," + ts.mean());
            o.add(p + "md,sd,xd=" + ts.md() + "," + ts.sd() + "," + ts.xd());
            o.add(p + "min,max,mindt,maxdt=" + ts.min() + "," + ts.max() + "," + ts.mindt() + "," + ts.maxdt());
            o.add(p + "slope,momentum,yend,yinit=" + ts.slope() + "," + ts.momentum() + "," + ts.yend() + "," + ts.yinit());
            o.add(p + "r2,bump,zscore,lastdevz=" + ts.r2() + "," + ts.bumpiness() + "," + ts.zscore() + "," + ts.lastdevz());
            o.add(p + "median,ntile=" + ts.median() + "," + ts.ntile(0.9));
            o.add(p + "maxgain,maxloss=" + ts.maxgain() + "," + ts.maxloss());
            o.add(p + "rangescore,discount,rebound,cagr=" + ts.rangescore() + "," + ts.discount() + "," + ts.rebound() + "," + ts.cagr());
            o.add(p + "aroon=" + ts.aroonup() + "," + ts.aroondwn() + "," + ts.hangtime());
            o.add(p + "season=" + ts.seasonality() + "," + ts.seasonindex(3));
            o.add(p + "vol=" + ts.optvol() + "," + ts.optivol(0) + "," + ts.deepvol(5, 30) + "," + ts.rangevol(30));
            o.add(p + "rsi,kurt=" + ts.rsi() + "," + ts.kurtosis());
            o.add(p + "trisk=" + ts.tailrisktouch(0) + "," + ts.tailriskbreach(25) + "," + ts.tailrisk());
            o.add(p + "dr=" + dr.max_drs_ttm() + "," + dr.min_drs_ttm() + "," + dr.sharpness() + "," + dr.stickytrend());
            o.add(p + "ticks=" + dr.downtickspermonth(0) + "," + dr.downticks(1, 10));
            o.add(p + "corr=" + dr.corr(raw) + "," + dr.coskew(raw) + "," + dr.beta(raw) + "," + dr.dcorr(raw, Boolean.TRUE) + "," + dr.dcorr(raw, null));
            o.add(p + "ma=" + ts.sma(w[1], 20) + "," + ts.lma(w[1], 20) + "," + ts.ema(w[1], 50) + "," + ts.pctchg(7) + "," + ts.alpharoi(raw, 1.1));
            o.add(p + "minmaxduring=" + ts.maxduring(30) + "," + ts.minduring(30) + "," + ts.maxuntil(90));
        }
        ts.setWindow(20211231, 33.0);
        o.add("prwindow=" + ts.count() + "," + ts.rangescore());
        ts.setWindow(20180101, 20211231);
        TimeSeriesData pc = Transforms.compute(new TimeSeries[]{ts}, 31, Transforms.PCTCHG);
        TimeSeriesData ov = Transforms.compute(new TimeSeries[]{ts}, 31, Transforms.OPTVOL);
        TimeSeries pcs = new TimeSeries(pc), ovs = new TimeSeries(ov);
        pcs.setWindow(Dates.dates[Dates.dates.length - 101], Dates.dates[Dates.dates.length - 1]);
        ovs.setWindow(Dates.dates[Dates.dates.length - 101], Dates.dates[Dates.dates.length - 1]);
        o.add("transforms=" + pc.last() + "," + ov.last() + "," + pcs.lastdevz() + "," + ovs.sma(20211231, 100) + "," + ovs.zscore());
        TimeSeriesData sum = Transforms.compute(ts.data(), dr.data(), Transforms.ADD);
        TimeSeriesData mul = Transforms.compute(ts.data(), 2.5, Transforms.MUL);
        o.add("arith=" + sum.last() + "," + mul.last() + "," + sum.get(100) + "," + mul.get(5));
        TreeMap<Integer, Double> ld = mul.load(new TreeMap<Integer, Double>(), 1);
        o.add("load=" + ld.size() + "," + ld.firstKey() + "," + ld.lastEntry().getValue());
        for (char t : new char[]{'c', 'p'}) {
            BSOption b = new BSOption(100, 105, 45 / 365.0, 0.3, t);
            double[] g = b.computeGreeks();
            o.add("bs." + t + "=" + b.computePrice() + "," + Arrays.toString(g) + "," + b.strike(t == 'c' ? 0.2 : -0.2) + "," + b.computeIV(3.1));
        }
        Corls c = new Corls();
        for (int k = 1; k <= 50; k++) c.process(k, Math.sqrt(k) * 3 + (k % 5));
        o.add("corls=" + c.slope() + "," + c.cons() + "," + c.corr() + "," + c.yval(12));
        return o;
    }

    public static void main(String[] args) {
        for (String s : run()) System.out.println(s);
    }
}
