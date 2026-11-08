package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;
import com.google.gson.stream.JsonReader;

public class StockOptionMsg {

	//price = 2 decimals, vol & greeks 6 decimals
	String contractID;//AAPL270115P00450000
	String ticker; //AAPL
	String type;	//put
	int asof;	//2025-01-10
	int expiration; //2027-01-15
	int strike;	//450.00
	int last;	//213.16
	int mark;	//213.15
	int bid;	//211.20
	int bid_size;	//10
	int ask;	//215.10
	int ask_size;	//8
	int volume;	//2
	int open_interest;	//0
	///// 6 decimals
	int implied_volatility;	//0.58720
	int delta;	//-0.59831
	int gamma;	//0.00196
	int theta;	//-0.03587
	int vega;	//1.29993
	int rho;	//-7.14585
	int upx; 
	
	public String toString() {
		return contractID + 
			"," + ticker + 
			"," + expiration + 
			"," + strike + 
			"," + type + 
			"," + last +
			"," + mark + 
			"," + delta + 
			"," + gamma +
			"," + theta; 
	}

	public static boolean generate(String sym,List<StockOptionMsg> res) throws Exception
    {
		try {
			res.clear();
	        // CHANGED (boundary): API key from env, rate limited
	        String body = com.mystock.ingest.Http.get(com.mystock.ingest.Http.alphaVantageUrl(
	            "function=HISTORICAL_OPTIONS&symbol=" + URLEncoder.encode(sym, "UTF-8")));
	        JsonReader reader = new JsonReader(new StringReader(body));
	        reader.beginObject();
	        while (reader.hasNext())
	        {
				String name  = reader.nextName();
				if (name.equals("data")) {
					reader.beginArray();
					while (reader.hasNext()) {
						reader.beginObject();
						StockOptionMsg msg = new StockOptionMsg();
						while (reader.hasNext()) {
							name = reader.nextName();
							String value = reader.nextString();
							if (name.equals("contractID")) msg.contractID = value;
							else if (name.equals("symbol")) msg.ticker = value;
							else if (name.equals("expiration"))
								msg.expiration = Const.intvalue(value.replaceAll("-",""),1);
							else if (name.equals("strike"))
								msg.strike = Const.intvalue(value,100);
							else if (name.equals("type"))
								msg.type = value.substring(0,1).toUpperCase();
							else if (name.equals("last"))
								msg.last = Const.intvalue(value,100);
							else if (name.equals("mark"))
								msg.mark = Const.intvalue(value,100);
							else if (name.equals("bid"))
								msg.bid = Const.intvalue(value,100);
							else if (name.equals("bid_size"))
								msg.bid_size = Const.intvalue(value,1);
							else if (name.equals("ask"))
								msg.ask = Const.intvalue(value,100);
							else if (name.equals("ask_size"))
								msg.ask_size = Const.intvalue(value,1);
							else if (name.equals("volume"))
								msg.volume = Const.intvalue(value,1);
							else if (name.equals("open_interest"))
								msg.open_interest = Const.intvalue(value,1);
							else if (name.equals("implied_volatility"))
								msg.implied_volatility = Const.intvalue(value,10000);
							else if (name.equals("delta"))
								msg.delta = Const.intvalue(value,10000);
							else if (name.equals("gamma"))
								msg.gamma = Const.intvalue(value,10000);
							else if (name.equals("theta"))
								msg.theta = Const.intvalue(value,10000);
							else if (name.equals("vega"))
								msg.vega = Const.intvalue(value,10000);
							else if (name.equals("rho"))
								msg.rho = Const.intvalue(value,10000);
							else if (name.equals("date"))
								msg.asof = Const.intvalue(value.replaceAll("-",""),1);
						}
						res.add(msg);
						reader.endObject();
					}
					reader.endArray();
					} else {
						reader.skipValue();
					}
	        }
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
		return true;
    }

}
