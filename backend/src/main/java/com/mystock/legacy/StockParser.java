package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;
import com.google.gson.stream.JsonReader;

public class StockParser {

   public static void main(String[] args) throws Exception {
       execute();
   }

   public static void execute() throws Exception
   {
	   maxdt = -1; // CHANGED (boundary): legacy ran in a fresh JVM per task; reset static between runs
	   PriceDB db = new PriceDB();
	   StockStatusDB sdb = new StockStatusDB();
	   Map<String,String> stocks = sdb.load();
	   db.create();
	   sdb.open();db.open();
	   int count = 0;
       Set<String> inprocess = new HashSet<String>();
       inprocess.addAll(stocks.keySet());
	   int counter = 2, prevsize = 999999;
       for (int i=0;i<10;i++)
       {
           for (String ticker:stocks.keySet())
           {
			   if (ticker.indexOf("$") >= 0)
				   continue;
               if (!inprocess.contains(ticker))
                    continue;
		   	   if (!readprices(ticker,stocks.get(ticker), sdb, db)) {
					//commit any unprocessed first if any exception encountered
		   			sdb.close();db.close();
		 		    sdb.open();db.open();
		   	   }
               else inprocess.remove(ticker);
			   if (count++%100 == 99) {
                    sdb.close();db.close();
                    sdb.open();db.open();
			   }
		   }
           System.out.println("Iteration " + i + " Size " + inprocess.size());
		   if (inprocess.size() == prevsize) --counter;
		   if (counter <= 0) break;
		   prevsize = inprocess.size();
           Thread.sleep(5000);
           //if (true) break;
       }
	   db.close();
	   sdb.close();
   }

   public static int intpx(String str) {
	   double d = Double.parseDouble(str);
	   d *= 100;
	   return (int)d;
   }

   static int maxdt = -1;
   static final int MIN_DT = 20141231;

   //Reads single stock price history since lastupdate date to latest
   public static boolean readprices(String stk, String sym_dt_px_csv, StockStatusDB sdb, PriceDB pxdb) {
	   try {
	   		String[] arr = sym_dt_px_csv.split(",");
	   	 	int sym = Integer.parseInt(arr[0]), dt = Integer.parseInt(arr[1]), px = Integer.parseInt(arr[2]);
	   	 	if (maxdt > 0 && dt >= maxdt) {
                System.out.println("Skipping " + stk);
                return true;
            } else {
                System.out.println("Processing " + stk);
            }

	        // CHANGED (boundary): API key from env, rate limited, HTTPS via shared client; throttle notes detected
	        String body = com.mystock.ingest.Http.get(com.mystock.ingest.Http.alphaVantageUrl(
	            "function=TIME_SERIES_DAILY_ADJUSTED&outputsize=full&datatype=csv&symbol=" + URLEncoder.encode(stk, "UTF-8")));
	        if (com.mystock.ingest.Http.isThrottleNote(body)) throw new Exception("Throttled or error note from Alpha Vantage");
	        BufferedReader reader = new BufferedReader(new StringReader(body));
	 	    reader.readLine(); //skip header
	        String str = null;
	 	    String[] first = null;
		    //System.out.println("Upserting " + stk);
	        while ((str = reader.readLine()) != null)
			{
               //System.out.println("Input '"+str+"'");
	 		   String[] s = str.split(",");
	 		   if (first == null) first = s;
	 		   int recdt = Integer.parseInt(s[0].replaceAll("-",""));
			   if (recdt <= MIN_DT) break;
	 		   //timestamp	OHLC	adjusted_close	volume	dividend_amount	split_coefficient
	 		   pxdb.upsert(sym, recdt, intpx(s[1]), intpx(s[2]), intpx(s[3]), intpx(s[4]), intpx(s[5]), Long.parseLong(s[6]));
	 		   if (dt == recdt && intpx(s[5]) == px) break;
			   else if (dt == recdt) dt = MIN_DT; //split adjust has happened since last eval. Deep load full history
	 		   //System.out.println(str);
	        }
			int recdt = Integer.parseInt(first[0].replaceAll("-",""));
			maxdt = Math.max(maxdt,recdt);
		    //System.out.println("Upserted " + stk);
	 	    sdb.upsert(sym, recdt, intpx(first[5]));
	        reader.close();
            return true;
	   } catch (com.mystock.ingest.NotConfiguredException e) {
		   throw e; // ADDED: abort the job instead of retrying every ticker without a key
	   } catch (Exception e) {
		   System.out.println("Stock " + stk + " not processed : " + e.getMessage());
	   }
       try { Thread.sleep(1000); } catch (Exception e) {}
	   return false;
   }

    public static boolean readsymbols(String sym,Map<String,String> map) throws Exception
    {
		boolean res = false;
		try {
	        // CHANGED (boundary): API key from env, rate limited
	        String body = com.mystock.ingest.Http.get(com.mystock.ingest.Http.alphaVantageUrl(
	            "function=OVERVIEW&symbol=" + URLEncoder.encode(sym, "UTF-8")));
	        JsonReader reader = new JsonReader(new StringReader(body));
	        reader.beginObject();
			int col = 1;
	        while (reader.hasNext())
	        {
	              String name  = reader.nextName();
	              String value = reader.nextString();
				  if (name.toLowerCase().equals("note")) {
					  System.out.println("Throttled " + sym);
				  	  return false;
				  }
				  map.put(name,value);
				  res = true;
	        }
			//if (res) System.out.println(sym);
	        reader.endObject();
	        reader.close();
		}
		catch (com.mystock.ingest.NotConfiguredException e) {
		   throw e;
		}
		catch (Exception e) {
 		   System.out.println("Stock " + sym + " not processed : " + e.getMessage());
 		   return false;
		}
		return res;
    }

    public static int loadsymbols(StockDB db, boolean full, String filename, boolean header) {
		int failcnt = 0;
		try {
			failcnt = loadsymbolsprivate(db,full,filename,header);}
		catch (com.mystock.ingest.NotConfiguredException e) {
			throw e;
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		return failcnt;
	}
	
    //Reads from output of get_symbols.sh (symbols.txt) and loads into sqllite db = usstocks.db, tbl=stocks
	//Here full = false will still load any etfs in Const.etfs and their description
    private static int loadsymbolsprivate(StockDB db, boolean full, String filename, boolean header) throws Exception
    {
        int result = 0;
 		db.open();
      	BufferedReader input = new BufferedReader(new FileReader(filename));
		if (header) input.readLine();//skip header
   		String str = null;
 		int i = 0;
 		Map<String,String> map = new HashMap<String,String>();
   		while ((str = input.readLine())!=null) {
			if (str.indexOf("File Creation") > 0)
				continue;
 			String[] csv = str.split("\\|");
 			//System.out.println(csv[1]);
 			map.clear();
            if (csv.length == 1) continue;
 			if (db.loaded(csv[1]))
 				continue;
            if (csv[1].indexOf(".") >= 0 || csv[1].indexOf("$") >= 0)
                continue;
 			if (csv[5].equals("Y")) { //etf
				map.put("Description",csv[2]);
				map.put("Name",csv[2]);
                db.upsert(csv[1], map);
			} else if (full && readsymbols(csv[1],map)) {
 				if (map.get("Description") != null)
 					db.upsert(csv[1], map);
                else {
                    result++;
                    System.out.println(csv[1] + "\t" + map);
                }
            }
   		}
 		db.close();
 		input.close();
        return result;
 	}

  // ADDED: prefer the HTTPS symbol directory, fall back to the legacy URL (ftp) on failure.
  public static final String NASDAQ_HTTPS = "https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqtraded.txt";
  public static boolean wGet(String saveAsFile, String urlOfFile) {
    try {
      String body = com.mystock.ingest.Http.get(NASDAQ_HTTPS);
      if (body.indexOf('|') > 0) {
        java.nio.file.Files.writeString(java.nio.file.Path.of(saveAsFile), body);
        return true;
      }
    } catch (Exception e) {
      System.out.println("HTTPS symbol download failed, trying " + urlOfFile + ": " + e.getMessage());
    }
    return wGetLegacy(saveAsFile, urlOfFile);
  }

  public static boolean wGetLegacy(String saveAsFile, String urlOfFile) {
    InputStream httpIn = null;
    OutputStream fileOutput = null;
    OutputStream bufferedOut = null;
    try {
      // check the http connection before we do anything to the fs
      httpIn = new BufferedInputStream(new URL(urlOfFile).openStream());
      // prep saving the file
      fileOutput = new FileOutputStream(saveAsFile);
      bufferedOut = new BufferedOutputStream(fileOutput, 1024);
      byte data[] = new byte[1024];
      boolean fileComplete = false;
      int count = 0;
      while (!fileComplete) {
        count = httpIn.read(data, 0, 1024);
        if (count <= 0) {
          fileComplete = true;
        } else {
          bufferedOut.write(data, 0, count);
        }
      }
    } catch (MalformedURLException e) {
      return false;
    } catch (IOException e) {
      return false;
    } finally {
      try {
		if (bufferedOut != null)
        	bufferedOut.close();
		if (fileOutput != null)
        	fileOutput.close();
		if (httpIn != null)
        	httpIn.close();
      } catch (IOException e) {
        return false;
      }
    }
    return true;
  }

}
