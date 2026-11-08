package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.stream.JsonReader;
import java.time.*;
import java.time.format.*;

/*
New symbol :
    1. Get UPX+EARN from Yahoo
    2. Get IV+EARN from db, if not then from Yahoo
    3. Start caching UPX,IV,EARN
ELSE
    4. return from cache
*/
public class PVCache {

    private static final Map<String,double[]> pvcache = new ConcurrentHashMap<String,double[]>();
    private static long pricems = 0;
    private static long volms   = 0;
    // CHANGED (boundary): refresh thread is started lazily on first register() instead of in a static
    // initializer, and is a daemon so it never blocks JVM exit.
    private static volatile Thread pvcachethread;
    private static StockVolDB db;

    // ADDED: offline fallback hook (last close / stock_metrics vol) used when live sources give nothing.
    public interface Fallback { double[] get(String sym); }
    public static volatile Fallback fallback = null;

    public static double[] register(String sym) {
        sym = sym.toUpperCase();
        if (pvcachethread == null) startThread();
        double[] d = pvcache.get(sym);
        try {
            if (d == null) {
                d = new double[] {0,0,0};
                pvcache.put(sym,d);
                // CHANGED (boundary): Finnhub first when configured (legacy used it only for refresh);
                // Yahoo scrape (legacy mktpx) otherwise; each failure is tolerated.
                try { mktpx1(sym); } catch (Exception e) { }
                if (d[0] == 0) { try { mktpx(sym); } catch (Exception e) { } }
                try { mktvol(sym); } catch (Exception e) { }
                applyFallback(sym, d);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return new double[] { 0,0,0 };
        }
        return d;
    }

    private static void applyFallback(String sym, double[] d) {
        Fallback f = fallback;
        if (f == null) return;
        double[] fb = f.get(sym);
        if (fb == null) return;
        if (d[0] == 0) d[0] = fb[0];
        if (d[1] == 0) d[1] = fb[1];
        if (d[2] == 0) d[2] = fb[2];
    }

    private static synchronized void startThread() {
        if (pvcachethread != null) return;
        pvcachethread = getCacheThread();
    }

    public static Thread getCacheThread() {
        Thread th = new Thread() {
            public void run() {
                long pricems = 0;
                boolean fetch = false;
                while (true) {
                    long l = System.currentTimeMillis();
                    for (Map.Entry<String,double[]> entry : pvcache.entrySet()) {
                        try {
                            if (fetch) {
                                try { mktpx1(entry.getKey()); }
                                catch (com.mystock.ingest.NotConfiguredException nc) { mktpx(entry.getKey()); }
                                Thread.sleep(3000);
                            } else {
                                Thread.sleep(100);
                            }
                        } catch (Exception e) {
                        }
                    }
                    fetch = (l - pricems) > (2 * 60 * 1000);
                    if (fetch) pricems = l;
                }
            }
        };
        th.setDaemon(true);
        th.setName("pvcache-refresh");
        th.start();
        return th;
    }

    // public static void refreshPrice()
    // {
    //     long l = System.currentTimeMillis();
    //     if (l - pricems < (2 * 60 * 1000)) return;
    //     for (Map.Entry<String,double[]> entry : pvcache.entrySet()) {
    //     }
    //     pricems = l;
    // }

    // public static void refreshVol()  {
    //     long l = System.currentTimeMillis();
    //     if (l - volms < (60 * 60 * 1000)) return;
    //     for (Map.Entry<String,double[]> entry : pvcache.entrySet()) {
    //         try {
    //             mktvol(entry.getKey());
    //         } catch (Exception e) {
    //         }
    //     }
    //     volms = l;
    // }

   public static void main(String[] args) throws Exception
   {
       printIV(args[0]);
       //for (double s:mktstats(args[0]))
       //    System.out.println(s);
   }

   //will not work for recursive tags
   private static List<String> tokenize(String txt, String tag) {
        List<String> res = new ArrayList<String>();
        int beg = -1;
        while ((beg = txt.indexOf("<"+tag)) >= 0) {
            int end = txt.indexOf("</"+tag);
            res.add(txt.substring(beg,end));
            txt = txt.substring(end + tag.length()+3);
        }
        return res;
   }

   private static void mktpx(String sym) throws Exception {
       String txt = yahoo(sym);
       double upx = Double.parseDouble(parse(txt, "fin-streamer", new String[] {sym,"regularMarketPrice"}, 0));
       String ern = parse(txt, "td", new String[] {"EARNINGS_DATE-value"}, 1);
       if (ern != null && ern.length() > 1) {
           String dt = LocalDate.parse(ern, DateTimeFormatter.ofPattern("MMM dd, uuuu", Locale.UK)).
            format(DateTimeFormatter.ofPattern( "uuuuMMdd" , Locale.UK ));
            pvcache.get(sym)[2] = Double.parseDouble(dt);
       }
       pvcache.get(sym)[0] = upx;
   }

   // CHANGED: legacy hard-coded 2024 expiries; generated dynamically now.
   static int[] exp24 = Const.monthlyExpiries();
   public static void printIV(String sym) throws Exception
   {
       String txt = yahoo(sym);
       double upx = parsedbl(parse(txt, "fin-streamer", new String[] {sym,"regularMarketPrice"}, 0));
       int px = (int)upx+5;
       px = (px/10)*10;
       Context.log("Price",px);

       Map<Integer,Double> dtmap = new TreeMap<Integer,Double>();
       for (int dt:exp24) {
           int tdt = dt;
           double vol = readmktvol(sym, (double)px, tdt);
           dtmap.put(tdt,vol);
       }
       for (int key:dtmap.keySet()) {
           System.out.println(key+"\t\t"+dtmap.get(key));
       }
   }


   //long earn, long pe, long mktcap, long iv
   public static double[] mktstats(String sym) throws Exception {
       sym = sym.toUpperCase();
       double[] res = new double[4];
       String txt = yahoo(sym);
       double upx = parsedbl(parse(txt, "fin-streamer", new String[] {sym,"regularMarketPrice"}, 0));
       String ern = parse(txt, "td", new String[] {"EARNINGS_DATE-value"}, 1);
       if (ern != null && ern.length() > 1 && ern.indexOf("202") >= 0) {
           String dt = LocalDate.parse(ern, DateTimeFormatter.ofPattern("MMM dd, uuuu", Locale.UK)).
            format(DateTimeFormatter.ofPattern( "uuuuMMdd" , Locale.UK ));
            res[0] = parsedbl(dt);
       }
       res[1] = parsedbl(parse(txt, "td", new String[] {"PE_RATIO-value"}, 0));
       String mktcap = parse(txt, "td", new String[] {"MARKET_CAP-value"}, 0);
       char c = mktcap.charAt(mktcap.length()-1);
       res[2] = (c == '0')? 0:parsedbl(mktcap.substring(0,mktcap.length()-1));
       if (c == 'B') res[2] *= 1000.0;
       if (c == 'T') res[2] *= 1000000.0;
       res[3] = readmktvol(sym, upx, Const.NEXT_EXPIRY_DT);
       if (res[3] < 0) res[3] = 0;
       if (res[3] > 200) res[3] = 200;
       //for (double d:res) System.out.println(d);
       return res;
   }

   private static double readmktvol(String sym, double upx, int expdt) throws Exception {
       int px = (int)upx+3;
       px = (px/5)*5;
       String optsym = px + "000";
       while (optsym.length() != 8) optsym = "0" + optsym;
       optsym = sym + (expdt%1000000) + "C" + optsym;
       String txt = yahoo(optsym);
       double opx = Double.parseDouble(parse(txt, "fin-streamer", new String[] {optsym,"regularMarketPrice"}, 0));
       double dte = (Dates.dateToDays(expdt) - Dates.dateToDays(Dates.today()));
       System.out.println(optsym+","+opx+","+dte);
       BSOption opt = new BSOption(upx, px, dte/365, 0.20, 'c');
       return 100.0 * opt.computeIV(opx);
   }

   private static void mktvol(String sym) throws Exception {
       if (db == null) db = new StockVolDB();
       double[] d = db.read(sym);
       if (d[4] > 0) {
           pvcache.get(sym)[1] = d[3];
           pvcache.get(sym)[2] = d[0];
       }
       else
           pvcache.get(sym)[1] = readmktvol(sym,pvcache.get(sym)[0], Const.NEXT_EXPIRY_DT);
   }

   // CHANGED (boundary): shared HTTPS client with a short timeout; can be disabled with MYSTOCK_YAHOO_ENABLED=false.
   // The legacy concatenated the page without newlines; kept.
   private static String yahoo(String sym) throws Exception {
       if (!com.mystock.config.Config.get().yahooEnabled) throw new Exception("Yahoo scraping disabled");
       String body = com.mystock.ingest.Http.get("https://finance.yahoo.com/quote/" + URLEncoder.encode(sym, "UTF-8"),
               java.time.Duration.ofSeconds(8));
       return body.replaceAll("[\\r\\n]", "");
   }

   private static double parsedbl(String val) {
       double res = 0;
       try {
           if (val != null)
                res = Double.parseDouble(val.trim());
       } catch (Exception e) {
       }
       return res;
   }

   private static String parse(String txt, String tag, String[] filter, int numskiptokens) throws Exception
   {
        numskiptokens++; //yahoo added extra tag:  found on May 2024
       List<String> streamer = tokenize(txt, tag);
       for (String s:streamer) {
            int matches = 0;
           for (String f:filter)
                if (s.indexOf(f) >= 0) ++matches;
           if (filter.length != matches) continue;
           s = s.replaceAll(">","~");
           s = s.replaceAll("<","~");
           String[] arr = s.split("~");
           //Context.log("Inside parse", arr.length, numskiptokens, arr[arr.length-1-numskiptokens]);           
           return arr[arr.length-1-numskiptokens];
       }
       return "0";
   }

    private static void mktpx1(String sym) throws Exception
    {
        //https://polygon.io/
        //FinancialModelingPrep API
		//1275896de67e71c7082464b5353ee19ab4602de8 - TIINGO
        // CHANGED (boundary): token from FINNHUB_API_KEY (throws NotConfiguredException when absent)
        String strurl = "https://finnhub.io/api/v1/quote?symbol="+URLEncoder.encode(sym, "UTF-8")+"&token="+com.mystock.ingest.Http.finnhubKey();
        JsonReader reader = new JsonReader(new StringReader(com.mystock.ingest.Http.get(strurl, java.time.Duration.ofSeconds(10))));
		reader.beginObject();
		double[] sohlc = new double[5];
        while (reader.hasNext())
        {
              String name  = reader.nextName();
              String value = reader.nextString();
			  if (name.equals("c"))  sohlc[0] = Double.parseDouble(value);
			  if (name.equals("o"))  sohlc[1] = Double.parseDouble(value);
			  if (name.equals("h"))  sohlc[2] = Double.parseDouble(value);
			  if (name.equals("l"))  sohlc[3] = Double.parseDouble(value);
			  if (name.equals("pc")) sohlc[4] = Double.parseDouble(value);
        }
        reader.endObject();
        reader.close();
        if (sohlc[0] > 0) pvcache.get(sym)[0] = sohlc[0]; // CHANGED: never overwrite with 0 (unknown symbol)
    }
}
