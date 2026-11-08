package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;
import com.google.gson.stream.JsonReader;

public class StockOxParser {

/*
with t1 as (select symbol,avg(implied_volatility)/100 iv,max(upx)/100 px from quotes 
where abs(5000-abs(delta)) < 1000 and upx > 10000 group by 1),
t2 as (select sf.symbol,qdrop,qdropavg,optvol,triskvol from stockfeatures sf
JOIN (SELECT symbol, MAX(asof) AS max_asof FROM stockfeatures GROUP BY symbol
) latest ON sf.symbol = latest.symbol AND sf.asof = latest.max_asof)
select t1.*,t2.* from  t2 join t1 on t1.symbol=t2.symbol 
where iv - triskvol > -5 order by iv - optvol desc limit 10;
*/

   public static void main(String[] args) throws Exception {
		Context.init();
		execute();
		executeFeatures();
	}
   
	public static void executeFeatures() throws Exception {
		StockFeatureDB odb = new StockFeatureDB();
		int lastdt = Dates.lastdt();
		odb.create();
		odb.open();
		StockFeature ref = new StockFeature(Const.MKTETF);
		for (String stk: Context.symbols.keySet()) {
			try { 
				System.out.println("Processing ticker " + stk );
				StockFeature f = new StockFeature(stk);
				f.ref(ref);
				ref.set(lastdt);
				f.set(lastdt);
				//System.out.println(f.corr());
				odb.upsert(stk, lastdt, f); 
			}
			catch (Exception e) { 
				odb.close(); odb.open(); 
			}
		}
		odb.close();
	}

   public static void execute() throws Exception
   {
		StockStatusDB sdb = new StockStatusDB();
		OptionPriceDB odb = new OptionPriceDB();
		odb.create();
		odb.open();
		Map<String,String> stocks = sdb.load();
		List<StockOptionMsg> msgs = new ArrayList<StockOptionMsg>();
		Map<Integer,Integer> expr = new HashMap<Integer,Integer>();
	
		Set<String> processed = new HashSet<String>();
		for (int i=0;i<5;i++) 
		{
			for (String ticker:stocks.keySet())
			{
				if (processed.contains(ticker)) continue;
				//Get Underlying price
				String[] vals = stocks.get(ticker).split(",");
				int upx = Integer.parseInt(vals[2]);
				if (upx < 5000) continue; 
				boolean res = StockOptionMsg.generate(ticker,msgs);
				if (msgs.size() > 0) {
					processed.add(ticker);
					//Skip ones that donot have weekly
					boolean hasweekly = false;
					expr.clear();
					for (StockOptionMsg msg:msgs) {
						if (msg.expiration % 100 < 10)
							hasweekly = true;
						expr.put(msg.expiration/100,msg.expiration);
					}
					if (hasweekly == false && msgs.size() > 0) continue;
					System.out.println(ticker+":"+msgs.size());
					//Upsert the remaining usuable tickers
					for (StockOptionMsg msg:msgs) {
						msg.upx = upx;
						int exprdt = expr.get(msg.expiration/100).intValue(); 
						if (msg.strike % 1000 == 0 && exprdt == msg.expiration)
							odb.upsert(msg);
					}
				}
			}
		}
		odb.close();
   }

}
