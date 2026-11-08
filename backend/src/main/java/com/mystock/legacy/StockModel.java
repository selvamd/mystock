package com.mystock.legacy;

import java.net.*;
import java.io.*;
import java.util.*;

public class StockModel
{
	private TreeMap<Double,StockInfo> topscore = new TreeMap<Double,StockInfo>();


	public void process(StockInfo info)
	{
		// Detecting various conditions (mon-fri cycles)
		//     - Use mompnl,momtr to see if bull or bear or neutral or volatile
		//     - Use wow to see sub-type:
		//         - max(dr) > 2sd  => shock
		//         - avg(dr) high   => raging
		//         - avg(dr) low    => aging

		// lastdevz,rangescore,prscore - longer Trends
		// px2bbpct,mompnl,momdip - last trend
		// weekdip,weekpnl - momentum

		if (info.getd("mktcap") > 0 && info.getd("mktcap")/1e9 < 50) return;
		double score = 0;
		score += info.getd("rangescore")/100;
		score += info.getd("prscore")/100;
		score += Math.min(Math.max(0,info.getd("px2bbpct")),100.0)/100.0;
		score += Math.min(Math.max(0,0.5+info.getd("momdip")/6.0),100.0)/100.0;
		topscore.put(-1*score,info);
	}

	public static void main(String[] args)
		throws Exception
    {
		//Const.path = "./docker/data/";
		boolean predonly = args.length > 0 && args[0].equals("pred");
		execute(predonly);
	}

    public static void execute(boolean predonly)
		throws Exception
	{
		Context.init();
		StockFeature mkt = new StockFeature(Const.MKTETF);
		Map<String,StockFeature> sect = new HashMap<String,StockFeature>();
		sect.put("TECH",new StockFeature("QQQ"));
		sect.put("MANUFACTURING",new StockFeature("XLB"));
		sect.put("HEALTH",new StockFeature("XLV"));
		sect.put("FINANCE",new StockFeature("XLF"));
		sect.put("ENERGY",new StockFeature("XLE"));
		sect.put("TRADE",new StockFeature("XLP"));
		sect.put("REIT",new StockFeature("XLRE"));
		String symbolstr = Const.etf +
			",A,AABA,AAL,AAPL,AB,ACGL,ACN,ADBE,ADP,ADS,ADSK,AET,AFL,AGN,AIG,AKAM,ALG,ALGN,ALL,ALLY,ALNY,AMAT,AMCX,AMD,AMGN,AMTD,AMZN,ANET,ANF,ANTM,ANTX,APO,ARCC,ARCH,ARES,ARMH,ATHN,ATVI,AVGO,AXS,AZO,BA,BABA,BAC,BB,BBBY,BBU,BBY,BFR,BIDU,BIG,BK,BLK,BLMN,BMO,BNED,BNS,BSAC,BSMX,BURL,BWLD,BZUN,C,CAKE,CAR,CARS,CAT,CBOE,CBS,CEA,CEO,CG,CGNX,CHA,CI,CIEN,CMCM,CME,CMG,CNC,CNCE,COF,COP,COST,CPB,CRI,CRM,CSCO,CTSH,CTXS,CUDA,CVS,CVX,CY,DAL,DATA,DB,DE,DGX,DIS,DKS,DLTR,DNB,DNKN,DO,DPS,DPZ,DQ,DRI,DVMT,DVN,EBAY,EFX,EPC,EQIX,ERIC,ESRX,ETFC,EVC,EXPE,FB,FCAU,FDC,FDX,FEYE,FIS,FIT,FIVE,FOX,FOXA,FRC,GDDY,GGG,GILD,GM,GNW,GOOG,GOOGL,GPS,GRMN,GS,GT,HAL,HD,HDB,HLF,HMC,HOG,HOMB,HON,HP,HPE,HPQ,HRB,HSY,HTHT,HUM,IBKR,ILMN,IMAX,INFO,INFY,INTL,INTU,IPGP,IRBT,IRDM,IT,JACK,JBLU,JCP,JD,JNPR,JPM,JWN,K,KB,KBH,KHC,KMX,KOF,KR,KSS,L,LC,LEN,LMT,LOW,LPL,LQ,LUV,LYB,LZB,M,MA,MAR,MAT,MBLY,MCD,MCHP,MCO,MDB,MET,MIK,MKTX,MLNX,MOMO,MORN,MRVL,MS,MSI,MT,MU,NAVI,NCLH,NDAQ,NFLX,NKE,NLSN,NOW,NTAP,NTES,NTGR,NTRI,NVDA,ODP,OSK,OSTK,PANW,PAYC,PCG,PCLN,PFS,PGR,PHG,PII,PLAY,PLNT,PM,PRU,PTR,PUK,PYPL,PZZA,QCOM,QVCB,RACE,RAD,RCL,RDY,RGC,RHT,RL,ROKU,ROST,RY,SAGE,SAP,SAVE,SBUX,SCHW,SFLY,SHOP,SINA,SIX,SNAP,SNDR,SNE,SNP,SPGI,SPLK,SQ,STAY,STM,SUN,SWIR,SWKS,SWX,SYMC,TCBI,TD,TELL,TEX,TGB,TGT,THG,TIF,TIVO,TLRD,TLYS,TM,TMUS,TPX,TREE,TRI,TRIP,TROW,TRU,TSEM,TSLA,TTM,TWLO,TWTR,TWX,TXN,UAL,UNH,UNP,UPS,VEDL,VG,VIRT,VLO,VLP,VMW,VOD,VOYA,VPG,VRSN,VRTU,VRX,VVV,W,WAGE,WB,WDAY,WDC,WEN,WETF,WING,WINS,WIT,WIX,WLTW,WNS,WU,WYNN,XRX,YELP,YRD,YUM,YUMC,Z,ZG,ZNGA,ZNH,MSFT,V,JNJ,WMT,PG,PFE,XOM,ORCL,KO,CMCSA,PEP,ABT,VZ,ABBV,INTC,WFC,MRK,T,SONY,MRNA,EL,AXP,HSBC,SNOW,ABNB,BX,IBM,GE,TEAM,DG,UBER,PDD,DASH,DDOG,IBN,ZM,CCZ,FTNT,FISV,TTD,PSA,F,NXPI,UBS,ICE,OKTA,DELL,DOCU,SPOT,PLTR,AVB,NOK,CS,TW,WM";
		//symbolstr = "SPY,QQQ,TSLA,IWM,AMZN,AAPL,GOOGL,XLE,META,AMD,GOOG,NVDA,MSFT,GLD,BABA,TLT,PFE,XOM,DIS,JPM,PYPL,LQD,NFLX,OXY,SQ,SBUX,ATVI,KRE,KO,JD,BA,GE,XBI,MS,DIA,QCOM,USO,SQQQ,XOP,PANW,SLB,ABNB,CVX,MRK,NKE,WMT,BMY,CRM,ZM,IBM,ORCL,XLU,DVN,XLK,EMB,MRNA,XLV,JNJ,V,NEE,FSLR,XLP,PG,GILD,XLI,MET,SE,ABBV,SMH,TGT,DOW,TMUS,COP,IYR,WYNN,AMAT,HD,VGK,IEF,GS,CVS,BX,SCHW,CAT,AEM,SNOW,CRWD,UPS,ADBE,BIDU,TXN,FXI,EEM,KWEB";
		String[] symbols = symbolstr.split(",");
		if (predonly) {
			pred(symbols,sect,mkt);
			return;
		}
		train(symbols,sect,mkt);
		pred(symbols,sect,mkt);
	}

	public static void pred(String[] symbols,Map<String,StockFeature> sect, StockFeature mkt) throws Exception
	{
		String header = "ticker,name," + mkt.header("stk_") + "," + mkt.header("sect_") + "," + mkt.header("mkt_");
		BufferedWriter writer = new BufferedWriter(new FileWriter(Const.path+"pred.csv"));
		writer.write(header);
		writer.newLine();
		for (String stk: symbols)
		{
			if (!Context.symbols.containsKey(stk)) continue;
			StockFeature f = new StockFeature(stk);
			if (f.sect().equals("OTHER")) continue;
			//load sect etf
			StockFeature sec = sect.get(f.sect());
			if (sec == null) sec = mkt;
			String aline = stk + "," + f.name();
			aline += "," + f.values(mkt);
			aline += "," + sec.values(mkt);
			aline += "," + mkt.values(mkt);
			writer.write(aline);
			writer.newLine();
		}
		writer.close();
	}

	//Criteria for IC:
	//  abs(mompnl) < 3 and abs(rangescore-50) < 10 and abs(prscore-50) < 10 and (optivol - optvol) < 5 and abs(histvol - optvol) < 5 and mktcap > 10 and xadv < 1 and volratio > 0.8
	public static void train(String[] symbols,Map<String,StockFeature> sect, StockFeature mkt) throws Exception
	{
		String header = "ticker,trddt," + mkt.header("stk_") + "," + mkt.header("sect_") + "," + mkt.header("mkt_") + ",target";
		BufferedWriter writer = new BufferedWriter(new FileWriter(Const.path+"train.csv"));
		writer.write(header);
		writer.newLine();

		for (String stk: symbols)
		{
			if (!Context.symbols.containsKey(stk)) continue;
			StockFeature f = new StockFeature(stk);
			if (f.sect().equals("OTHER")) continue;
			//load sect etf
			StockFeature sec = sect.get(f.sect());
			if (sec == null) sec = mkt;

			double tgt = f.weekpnl();
			for (int dt = Dates.offset(Context.lastdt,-7);dt > 20220101; dt = Dates.offset(dt,-7)) {
				if (dt < f.price().firstdt()) continue;
				f.set(dt);
				mkt.set(dt);
				sec.set(dt);
				String aline = stk + "," + dt;
				aline += "," + f.values(mkt);
				aline += "," + sec.values(mkt);
				aline += "," + mkt.values(mkt);
				aline += "," + tgt;

				writer.write(aline);
				writer.newLine();
				tgt = f.weekpnl();
			}
		}
		writer.close();
	}

}
