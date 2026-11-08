#!python
import numpy as np
import pandas as pd
#import matplotlib.pyplot as plt
# CHANGED: 'import webbrowser' is deferred to webs.__process__; only the IPython shell opens a browser
OPEN_BROWSER = False
from .plots import *
from .option import *
from .utils import *
from .svcreader import *
from .optionprice import Option
import datetime
from inspect import signature
#from pycaret.regression import *

#import pylab as pl
import numpy as np

# CHANGED: urllib imports removed (webs.__test__ no longer probes third-party sites)

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)
pd.set_option('display.expand_frame_repr', False)
pd.set_option('display.float_format', '{:.2f}'.format)
pd.set_option('display.max_colwidth', None)

featuretype = {
    "facts":    "ticker,name,sect,ind,mktsize,mktcap,pe,eps,dividend,earnings,petype,shares"
    ,"mktperf": "daypnl,weekpnl,mompnl,qoqpnl,yoypnl,cagr,qpeak,qdrop,qrebound,qdropavg,qdropratio,recovery"
    ,"mktpx":   "lastpx,ema20,ema60,ema200,precovpx,decovpx"
    ,"mktrisk": "bumpiness,r2,stlpm,dtpm,range,kurt,sharpness,maxpctchg,maxdrs,mindrs"
    ,"mktlvl":  "rangescore,prscore,lastdevz,rsi,weekdip,momdip,xadv,momtr,aroonup,aroondwn,hangtime"
    ,"mktcorr": "corr,beta,alpha,upcorr,downcorr,coskew,mktrscore,mktdip,mktsindex"
    ,"mktdir":  "pnldir,px2bbpct,summ,cross,dpnl,peak30,drop30,seasonality,seasonindex"
    ,"mktvol":  "impvol,optvol,optivol,histvol,trisktouch,volratio,triskbreach,triskvol,volscore"
    ,"summary": "summ,mktcap,pe,lastpx,weekpnl,mompnl,rangescore,qdrop,px2bbpct,cross,xadv,optvol,volratio,earnings"
}


featuredict = {
    "symid": "Internal symbol id"
    ,"ticker":"Ticker name or symbol name or company name or stock name or an etf name"
    ,"name":"Stock name or description"
    ,"sect":"Sector that the stock belongs to. Can be HEALTH MANUFACTURING ENERGY TECHNOLOGY TRADE REIT FINANCE"
    ,"ind":"Industry for the stock."
    ,"mktsize":"Classifier based on market capitalization size. Can be 5B 10B 20B 50B 100B 250B 500B 1T"
    ,"mktcap":"Market capitalization based on last close price"
    ,"pe":"price-to-earnings or pe ratio"
    ,"eps":"Earnings per share or eps"
    ,"dividend":"dividend percentage"
    ,"shares":"Shares outstanding"
    ,"pnldir":"10 day exponential moving average or ema of daypnl as direction indicator"
    ,"daypnl":"Percent Growth gain or loss over a day"
    ,"weekpnl":"Percent Growth gain or loss over a week"
    ,"mompnl":"Percent Growth gain or loss over a month"
    ,"qoqpnl":"Percent Growth gain or loss over a quarter"
    ,"yoypnl":"Percent Growth gain or loss over a year"
    ,"cagr":"Compound annual growth rate or cagr for a year"
    ,"corr":"One Year Correlation with overall market. Ranges from -1 to +1"
    ,"upcorr":"One Year Correlation with overall market only when market trends up. Ranges from -1 to +1"
    ,"downcorr":"One Year Correlation with overall market only when market trends down. Ranges from -1 to +1"
    ,"coskew":"Net return expected in exaggerated market volatility conditions. Net Asset return weighting by squared market return"
    ,"seasonality":"Measures how much seasonal the data is. Absolute price change after excluding trend averaged for all 12 months"
    ,"seasonindex":"Measures how seasonal current month is. Realized price change for the given month after excluding the trend"
    ,"xadv":"Measures how active current trading is compared to average. Ratio of last 5 day average trading volume to average trading volume over a longer 100 day period"
    ,"signal":"Price breakout signal in either direction"
    ,"aroonup":"Time strength for most recent run up of market prices"
    ,"aroondwn":"Time strength for most recent drawdown of market prices"
    ,"hangtime":"Days drifted without a 2 percent price breakout on either direction"
    ,"peak30":"New peak attained in last 30 days"
    ,"drop30":"New bottom reached in last 30 days"
    ,"qpeak":"Percent increase in peak price in last 90 days"
    ,"qdrop":"Percent drop from its peak in last 90 days"
    ,"qdropavg":"Expected qdrop historically over a 12 month timespan"
    ,"qdropratio": "Number of avg daily moves it will take for qdrop to catch up to qdropavg. "
    ,"range": "Percent swing in the price for the entire year"
    ,"px2bbpct":"Price position as percent in bollinger band in 0 to 100 scale"
    ,"momtr":"Current price position in the trading range for 1 month in 0 to 100 scale"
    ,"rangescore":"Current price position in trading range for 1 year in 0 to 100 scale"
    ,"prscore":"Current price level within a trading range created from 66th to 100th percentile of 200 day moving average"
    ,"qrebound":"Magnitude of rebound from its last low"
    ,"precovpx":"Price at covid start"
    ,"decovpx":"Projected price today based on precovid trend"
    ,"lastdevz":"Delta between current price and one year trend price as zscore"
    ,"kurt":"Excess Kurtosis on daily return distribution of market prices"
    ,"trisktouch":"Measure of extreme swings. Touch percent of large price movements using 45 day 1 stdev threshold in last quarter"
    ,"triskbreach":"Measure of extreme moves. Breach percent of large price movements using 45 day 1 stdev threshold in last quarter"
    ,"impvol":"Latest Implied volatility in options market"
    ,"earnings":"Time to next earnings in days"
    ,"triskvol":"Theoritical volatility required to fully eliminate currently realized outlier risk. Volatility that creates 84 percent probability of profit for 15 delta option"
    ,"volscore":"Delta between Spot and average realized volatility as zscore"
    ,"histvol":"Mean realized volatility over last 100 days"
    ,"optvol":"Spot realized volatility over standard 21 day window"
    ,"optivol":"Spot realized volatility with recency bias for 21 day window"
    ,"maxpctchg":"Measures price shock factor for the stock. Maximum absolute daily return in last 50 days"
    ,"sharpness":"Measures tendency for extreme vs incremental moves. Fourth moment of daily returns from 100 days"
    ,"volratio":"Measures directional tendencies in longer timespans. Comparison of annualized cumulative volatilty over 49 days to annualized daily vol."
    ,"rsi":"Relative strength ind 0-100. Oversold less than 30. Over bought greater than 70"
    ,"weekdip":"Spot to average weekpnl delta as zscore"
    ,"momdip":"Spot to average mompnl delta as zscore"
    ,"bumpiness":"Measures volatility on trend predictions. Root mean square error RMSE measured around trend line"
    ,"r2":"Long range 5 year goodness of fit around trend line"
    ,"maxdrs":"max daily returns for trailing 12 months"
    ,"mindrs":"min daily returns for trailing 12 months"
    ,"stlpm":"Frequency of 2 percent loss days in a month"
    ,"dtpm":"Downtick days in a month"
    ,"ema20":"Measures the price after eliminating ultra short term noise. Spot 20 day moving average"
    ,"ema60":"Measures the price after eliminating mid term noise. Spot 60 day moving average"
    ,"ema200":"Measures the price after eliminating long term noise. Spot 200 day moving average"
    ,"beta":"Market correlated component of stock growth"
    ,"alpha":"Intrinsic growth shown by the stock after discounting market growth"
    ,"lastpx":"latest closing price"
    ,"avgdr": "Average absolute daily return pct in last 30 trading days "
    ,"summ":"Last month pxchg showing direction shifts"
    ,"pred":"Model based price predictor for next day"
    ,"petype":"Classifier by PE ratio. 0-NONE 10-LOW 20-FAIR 50-HIGH XTREME"
    ,"recovery":"Price drop that is clawed back rebound/rebound+drop"
    ,"dpnl":"Worst annualized roi across time periods year quarter or month"
    ,"cross" : "Moving average based momentum indicator. Can be Bullish Bear2Bull+ Bear2Bull Bull2Bear Bull2Bear+ Bearish"
    ,"mktrscore" : "rangescore of spy for comparision"
    ,"mktdip" : "currpx to 1 yr trendpx delta of SPY in sd scale"
    ,"mktsindex" : "Avg px swings off the trend for spy"
}

featuredict1 = {
    'ticker': "Ticker symbol for stock/etf",
    'name': "Stock name or description ",
    'sect': "Sector HEALTH,MANUFACTURING,ENERGY,TECH,TRADE,REIT,FINANCE,OTHER",
    'ind': "Industry name",
    'mktsize': "Classifier mktcap 5B,10B,20B,50B,100B,250B,250B+",
    'symid': "Internal symbol id",
    'mktcap': "Market capitalization based on last close price",
    'pe': "PE ratio (0-9999)",
    'eps': "Earnings per share",
    'dividend': "Last divident issued",
    'shares': "Shares outstanding",
    'pnldir': "10d ema of daypnl as direction indicator",
    'daypnl': "Gain/loss (%) previous day",
    'weekpnl': "Gain/loss (%) week over week",
    'mompnl': "Gain/loss (%) month over month",
    'qoqpnl': "Gain/loss (%) qtr over qtr",
    'yoypnl': "Gain/loss (%) year over year",
    'cagr': "Growth since Jun22 annualized",
    'corr': "1YR Correlation with SPY (-1 to +1, avg=0.6) ",
    'upcorr': "1YR Correlation during SPY upticks", 'downcorr': "1YR Correlation during SPY downticks",
    'coskew': "Expected net return in exaggerated mkt vol. Net Asset return after weighting by squared mkt return",
    'seasonality': "Avg Off-trend px change component for all 12 months",
    'seasonindex': "Realized monthly offtrend px chg (30*avg(offtrend/lastpx) for a given month",
    'xadv': "Ratio 5 day avg trading vol to 100 day avg trading vol (avg=1.0)",
    'signal': "Price breakout signal in either direction",
    'aroonup': "Timestrength (0-100) for ascend up",
    'aroondwn': "Timestrength (0-100) for descend down",
    'hangtime': "Days drifted without a significant price breakout (2% tolerance)",
    'peak30': "New peak in 30 days", 'drop30': "New bottom in 30 days",
    'qpeak': "Increase in peak price in last 90 days",
    'qdrop': "Magnitude of drop from its peak",
    'range': "Magnitude of price swing since mktpeak(EOY21)",
    'px2bbpct': "Price position as pct in 1M bollinger band (avg=50.0) ",
    'momtr': "Rangescore for trailing 1 month (0-100)",
    'rangescore': "Curr px in trading range since EOY21 (scale 0-100)",
    'prscore': "Curr px in a trading range (scale 0-100). Trading Range is based on 33% of ema200",
    'qrebound': "Magnitude of rebound from its low",
    'precovpx': "std px at covid start",
    'decovpx': "std px today based on precovid trend",
    'lastdevz': "currpx to 1 yr trendpx delta in sd scale (-3 to +3)",
    'kurt': "Excess Kurtosis on daily return  distribution",
    'trisktouch': "Touch percent of large (45DTE_15D) price changes in 45 days",
    'triskbreach': "Breach percent of large (45DTE_15D) price changes in 45 days",
    'impvol': "Implied vol from scaping yahoo",
    'earnings': "Next earnings date",
    'triskvol': "Required Vol for 66% POP at 15 delta (Probablilty of Profit)",
    'volscore': "Spot to avg vol delta as zscore",
    'histvol': "mean optvol over last 100 days",
    'optvol':  "Spot realized vol over std 21d window",
    'optivol': "Spot vol calculated with recency bias",
    'maxpctchg': "Max abs daily return in last 50 days",
    'sharpness': "4th moment for DR from 100 days",
    'volratio': "Compare long to daily vol. Ratio of 49-day-atr to 7 * 1-day-atr",
    'rsi': "Relative strength ind 0-100. Oversold < 30. Over bought > 70",
    'weekdip': "Spot to avg weekpnl delta as zscore",
    'momdip':  "Spot to avg mompnl delta as zscore",
    'bumpiness': "RMSE measured around trend line",
    'r2': "Long range (5Yr) Goodness of fit around trend line",
    'maxdrs': "max daily returns for trailing 12 months",
    'mindrs': "min daily returns for trailing 12 months",
    'stlpm': "Frequency of 2% loss days in a month",
    'dtpm': "Downtick days in a month",
    'ema20': "Spot 20d mov avg",
    'ema60': "Spot 60d mov avg",
    'ema200': "Spot 200d movavg",
    'beta': "Mkt correlated component of stock growth",
    'alpha': "Intrinsic growth over and above beta",
    'lastpx': "latest closing price",
    'summ': "Last month pxchg showing direction shifts",
    'pred': "Model based predictor for next day",
    'petype': "Classifier by PE (growth vs value) 0-NONE,10-LOW,20-FAIR,50-HIGH,XTREME",
    'recovery': "Price drop that is clawed back (rebound/rebound+drop)",
    'dpnl': "Worst annualized roi across timeperiods - year, qtr or month",
    'cross': "mvavg based momentum (Bullish,Bear2Bull+,Bear2Bull,Bull2Bear,Bull2Bear+,Bearish)",
    'seasonality': 'Realized monthly offtrend px chg (30*avg(offtrend/lastpx) for a given month',
    'seasonindex': 'Avg Off-trend px change component for all 12 months'
}


def featureval(df, feat, pct = [0.15,25,50,75,99.85]):
    if df[feat].dtype in ['str','object']:
        return []
    dfv = df[feat]
    res = []
    for d in pct:
        res = res + [str(round(dfv.quantile(d/100.0),2))]
    return res

sect_etfs =  {
    "TECH" : "QQQ,XLK,XSD,XLC",
    "MANUFACTURING" : "XLB,XLI,XSD",
    "HEALTH" : "XLV",
    "FINANCE" : "XLF,KRE,KBE",
    "ENERGY" : "XLE",
    "TRADE": "XLP,XLY",
    "REIT": "XLRE",
    "NONE": "SPY",
    "OTHER": "SPY"
}

def mktshare(port):
    print("usage: mktshare(port)")
    df = readprice("MKT","AWT")
    df.rename(columns={'val':'mkt'}, inplace=True)
    for tick in port.split(","):
        df1 = readprice(tick,"AWT")
        df = pd.merge(df1,df,how="left",on='asof')
        df[tick] = df['val']*100/df['mkt']
        df.drop(columns=['val'], inplace=True)
    title = "Mktshare of " + port + " vs Total Mkt"
    if len(df) == 0:
        print("No data")
        return
    return graph.trend(df, 'asof', port, title)

def totalmkt(port = "MKT"):
    print("usage: mktcum(port)")
    df = None
    for tick in port.split(","):
        df1 = readprice(tick,"AWT")
        df = df1 if df is None else pd.merge(df1,df,how="left",on='asof')
        df.rename(columns={'val':tick}, inplace=True)
    title = "Total Mktcap of " + port
    if len(df) == 0:
        print("No data")
        return
    return graph.trend(df, 'asof', port, title)

def timeseries(ticks="STK.SPY,STK.QQQ,STK.IWM,STK.XSD,STK.XLV,STK.XLF,STK.XLP,STK.XLE,STK.XLRE", pegdt=20211229):
    print("Usage: timeseries('STK.tick,EWT.port,MWT.port',yyyymmdd)")
    df = pd.DataFrame({"asof":[]})
    for tick in ticks.split(","):
        if pegdt == 0:
            df1 = readprice(tick.split(".")[1], tick.split(".")[0])
        else:
            df1 = readpricepeg(tick.split(".")[1], tick.split(".")[0], pegdt)
        df = pd.merge(df1,df,how="left",on='asof')
        df.rename(columns={'val':tick}, inplace=True)
    title = "TimeSeries tickers = " + ticks
    if len(df) == 0:
        print("No data")
        return
    return graph.line(df, 'asof', ticks, title)

def tsmetrics(ticks, metrics, timespan = 0):
    print("Usage: tsmetrics(ticks,metric)")
    print("Usage: metric = ROI,DAYPNL,WEEKPNL,MOMPNL,MOMDIP,QOQPNL,YOYPNL,DTPM,STLPM,OPTVOL,EMA20,EMA60,EMA200,RANGESCORE,LASTDEVZ,CORR,BUMPINESS")
    df = tsmetricsdata(ticks, metrics, timespan)
    csv = []
    for tick in ticks.split(","):
        for m in metrics.split(","):
            csv.append(tick+"/"+m)
    return graph.line(df, 'asof', ",".join(csv), "Timeseries for " + metrics)

def ffe(tick, type, n_harm = 10):
    print("Usage: ffe(stk_or_port_name,STK|EWT|MWT|MW) (Only MW is ts without stdpx scaling)")
    df = readprice(tick, type).tail(1000)
    x = df['val'].values[-1000:]
    df['asofdt'] = pd.to_datetime(df['asof'].map(str),format='%Y-%m-%d')
    dt = df['asofdt'].to_list()
    ex = fourierExtrapolation(x, 100, n_harm)
    arr = x.tolist()
    for i in range(100):
        arr.append(None)
        dt.append(dt[-1]+datetime.timedelta(days=1))
    df = pd.DataFrame({"asof":dt,"px":arr,"ffe":ex})
    return graph.line(df, "asof", "px,ffe", "Fourier Extrapolation")

def feature(skey = None):
    dict = featuredict
    show = "mktcap pe sect name ticker lastpx daypnl weekpnl mompnl qoqpnl yoypnl rangescore optvol optivol histvol maxpctchg volscore volratio xadv corr lastdevz r2 mktsize petype cross px2bbpct trisktouch triskbreach"
    df = pd.DataFrame()
    if skey is None:
        for k,v in dict.items():
            df = append_row(df,{"key":k,"value":v})
        print("\n Try an input category - facts,mktperf,mktdir,mktvol,mktcorr,mktpx")
    if skey in dict:
        df = append_row(df,{"key":skey,"value":dict[skey]})
    elif skey in featuretype:
        for key in featuretype[skey].split(","):
            df = append_row(df,{"key":key,"value":dict[key]})
    elif skey == 'show':
        for k,v in dict.items():
            if k in show.split():
                df = append_row(df,{"key":k,"value":dict[k]})
    elif skey is not None:
        for k,v in dict.items():
            if skey in show:
                df = append_row(df,{"key":k,"value":dict[k]})
    return df

def summaryrow(indf, df, span, vals):
    cols = "span win loss win%" if len(vals) == 3 else "span gainer loser unchg flip%"
    arr = cols.split()
    dict = { "span": span }
    for idx in range(len(arr)):
        if idx > 0: dict[arr[idx]] = vals[idx-1]
    df = append_row(df,dict)
    return df[cols.split()]

def summary_perf(df):
    print("\n~~~~~~~~~~ Market Performance (Advances/Declines/WinRate) ~~~~~~~~~~")
    span = "day,week,mom,qoq,yoy".split(",")
    rdf = pd.DataFrame()
    for idx, tm in enumerate(span):
        w = df.query(tm+ "pnl > 0").shape[0]
        l = df.query(tm+ "pnl < 0").shape[0]
        rdf = summaryrow(df,rdf,tm.upper(),[w,l,round((100*w)/(w+l),2)])
    rdf['Total'] = rdf.win + rdf.loss
    return rdf

def summary_trend(df):
    print("\n~~~~~~~~~~ Turnaround Trends (NetGainers/NetLosers/UnChanged/FlipRate) ~~~~~~~~~~")
    span = "day,week,mom,qoq,yoy".split(",")
    rdf = pd.DataFrame()
    for idx, tm in enumerate(span):
        ww = 0 if tm == 'yoy' else df.query(span[idx]+ "pnl > 0 and " + span[idx+1] + "pnl > 0").shape[0]
        wl = 0 if tm == 'yoy' else df.query(span[idx]+ "pnl > 0 and " + span[idx+1] + "pnl < 0").shape[0]
        lw = 0 if tm == 'yoy' else df.query(span[idx]+ "pnl < 0 and " + span[idx+1] + "pnl > 0").shape[0]
        ll = 0 if tm == 'yoy' else df.query(span[idx]+ "pnl < 0 and " + span[idx+1] + "pnl < 0").shape[0]
        rdf = summaryrow(df,rdf,tm.upper(),[wl,lw,ww+ll,round((100*(wl+lw))/(1+ww+ll+lw+wl),2)])
    rdf['Total'] = rdf.gainer + rdf.loser + rdf.unchg
    return rdf

def make_clickable(val):
    #https://finance.yahoo.com/quote/
    #https://www.zacks.com/stock/chart/GOOG/fundamental/price
    return '<a href="https://www.zacks.com/stock/chart/{}/fundamental/price" target="blank">{}</a>'.format(val,val)

def optstocks():
    ''' Stocks/Etfs with mktpx > 50 and has openint in top 100 on 20221230 '''
    return "SPY,QQQ,TSLA,IWM,AMZN,AAPL,HYG,GOOGL,XLE,META,AMD,GOOG,NVDA,MSFT,GLD,BABA,TLT,EFA,PFE,XOM,DIS,JPM,PYPL,LQD,NFLX,OXY,SQ,SBUX,ATVI,KRE,KO,JD,BA,GE,XBI,MS,DIA,QCOM,USO,SQQQ,XOP,PANW,SLB,ABNB,CVX,MRK,NKE,WMT,BMY,CRM,ZM,IBM,ORCL,XLU,DVN,XLK,EMB,MRNA,XLV,JNJ,V,NEE,FSLR,XLP,PG,GILD,XLI,MET,SE,ABBV,SMH,TGT,DOW,TMUS,COP,IYR,WYNN,AMAT,HD,VGK,IEF,GS,CVS,BX,SCHW,CAT,AEM,SNOW,CRWD,UPS,ADBE,BIDU,TXN,FXI,EEM,KWEB"

def loadvoldf(ctx):
    voldf = pd.DataFrame()
    port = {}
    for nv in "SPY=A,QQQ=A,IWM=A,SLV=M,GDX=M,GLD=M,XLE=E,USO=E,XOP=E,JD=C,FXI=C,BABA=C,KWEB=C,MRK=H,XLV=H,KRE=F,JPM=F,XLF=F,TGT=R,WMT=R,CVS=R,GOOG=T,AMZN=T,IBM=T".split(","):
        n,v = nv.split("=")
        port[n]=v
    for stk in optstocks().split(","):
        print(stk)
        d = spotvol(stk,None)
        d['ticker'] = stk
        d['port'] = 'X' if stk not in port else port[stk]
        voldf = append_row(voldf,d)

    voldf = pd.merge(voldf, ctx.df, how='inner', on='ticker')
    voldf['volatility'] = voldf.volatility.astype(float)
    #voldf['ivr'] = voldf.ivr #.astype(float)
    voldf['io'] = voldf.volatility - voldf.optvol
    voldf['ih'] = voldf.volatility - voldf.histvol
    voldf['oh'] = voldf.optvol - voldf.histvol
    voldf['oo'] = voldf.optivol - voldf.optvol
    voldf = voldf["ticker port lastpx volatility histvol volratio optvol optivol next_earnings_date sect weekpnl prscore rangescore xadv corr volscore oh io ih oo".split()]
    return voldf.sort_values('port ih'.split(),ascending=[True,False])

def tickers():
    return "A,AABA,AAL,AAPL,AB,ACGL,ACN,ADBE,ADP,ADS,ADSK,AET,AFL,AGN,AIG,AKAM,ALG,ALGN,ALL,ALLY,ALNY,AMAT,AMCX,AMD,AMGN,AMTD,AMZN,ANET,ANF,ANTM,ANTX,APO,ARCC,ARCH,ARES,ARMH,ATHN,ATVI,AVGO,AXS,AZO,BA,BABA,BAC,BB,BBBY,BBU,BBY,BFR,BIDU,BIG,BK,BLK,BLMN,BMO,BNED,BNS,BSAC,BSMX,BURL,BWLD,BZUN,C,CAKE,CAR,CARS,CAT,CBOE,CBS,CEA,CEO,CG,CGNX,CHA,CI,CIEN,CMCM,CME,CMG,CNC,CNCE,COF,COP,COST,CPB,CRI,CRM,CSCO,CTSH,CTXS,CUDA,CVS,CVX,CY,DAL,DATA,DB,DE,DGX,DIS,DKS,DLTR,DNB,DNKN,DO,DPS,DPZ,DQ,DRI,DVMT,DVN,EBAY,EFX,EPC,EQIX,ERIC,ESRX,ETFC,EVC,EXPE,FB,FCAU,FDC,FDX,FEYE,FIS,FIT,FIVE,FOX,FOXA,FRC,GDDY,GGG,GILD,GM,GNW,GOOG,GOOGL,GPS,GRMN,GS,GT,HAL,HD,HDB,HLF,HMC,HOG,HOMB,HON,HP,HPE,HPQ,HRB,HSY,HTHT,HUM,IBKR,ILMN,IMAX,INFO,INFY,INTL,INTU,IPGP,IRBT,IRDM,IT,JACK,JBLU,JCP,JD,JNPR,JPM,JWN,K,KB,KBH,KHC,KMX,KOF,KR,KSS,L,LC,LEN,LMT,LOW,LPL,LQ,LUV,LYB,LZB,M,MA,MAR,MAT,MBLY,MCD,MCHP,MCO,MDB,MET,MIK,MKTX,MLNX,MOMO,MORN,MRVL,MS,MSI,MT,MU,NAVI,NCLH,NDAQ,NFLX,NKE,NLSN,NOW,NTAP,NTES,NTGR,NTRI,NVDA,ODP,OSK,OSTK,PANW,PAYC,PCG,PCLN,PFS,PGR,PHG,PII,PLAY,PLNT,PM,PRU,PTR,PUK,PYPL,PZZA,QCOM,QVCB,RACE,RAD,RCL,RDY,RGC,RHT,RL,ROKU,ROST,RY,SAGE,SAP,SAVE,SBUX,SCHW,SFLY,SHOP,SINA,SIX,SNAP,SNDR,SNE,SNP,SPGI,SPLK,SQ,STAY,STM,SUN,SWIR,SWKS,SWX,SYMC,TCBI,TD,TELL,TEX,TGB,TGT,THG,TIF,TIVO,TLRD,TLYS,TM,TMUS,TPX,TREE,TRI,TRIP,TROW,TRU,TSEM,TSLA,TTM,TWLO,TWTR,TWX,TXN,UAL,UNH,UNP,UPS,VEDL,VG,VIRT,VLO,VLP,VMW,VOD,VOYA,VPG,VRSN,VRTU,VRX,VVV,W,WAGE,WB,WDAY,WDC,WEN,WETF,WING,WINS,WIT,WIX,WLTW,WNS,WU,WYNN,XRX,YELP,YRD,YUM,YUMC,Z,ZG,ZNGA,ZNH,MSFT,V,JNJ,WMT,PG,PFE,XOM,ORCL,KO,CMCSA,PEP,ABT,VZ,ABBV,INTC,WFC,MRK,T,SONY,MRNA,EL,AXP,HSBC,SNOW,ABNB,BX,IBM,GE,TEAM,DG,UBER,PDD,DASH,DDOG,IBN,ZM,CCZ,FTNT,FISV,TTD,PSA,F,NXPI,UBS,ICE,OKTA,DELL,DOCU,SPOT,PLTR,AVB,NOK,CS,TW,WM"

def cols(type,flds=None):
    if type is not None and type in featuretype:
        col = "ticker,name,sect,lastpx," + featuretype[type]
    else:
        col = "ticker,lastpx,name,pe,mktcap,sect,daypnl,weekpnl,mompnl,qoqpnl,yoypnl,range,"
        col = col + "rangescore,prscore,optvol,histvol,volscore,xadv,beta,corr,dpnl,cross"
    if flds is not None:
        for fld in flds.split(","):
            if fld not in col:
                col = col + "," + flds
    return col.split(',')

def heat(df,featexpr):
    df1 = pd.DataFrame()
    df1['derived'] = df.eval(featexpr)
    return graph.heatmap(df1.join(df), 'sect', 'ticker', 'mktcap', 'derived')

def distribution(df,feat,filt=None):
    if filt is not None:
        #filt = "mktcap >= 0"
        df = df.query(filt)
    dfv = df.eval(feat)
    # Now create histogram to show overlay with normal dist
    mu = dfv.agg('mean')
    sd = dfv.agg('std')
    s = np.random.normal(mu, sd, len(dfv))
    dfw = pd.DataFrame({"gaussian": s, "actual": dfv.to_list()})
    return graph.hist(dfw,"gaussian,actual")

def describe(df,feat,filt=None):
    if filt is None:
        filt = "mktcap >= 0"
    df = df.query(filt)
    dfv = df.eval(feat)

    # Just describe the distribution statistically
    #for s in "count,mean,median,std,skew,kurt,min,max".split(","):
    #    print(s + "\t" + f'{dfv.agg(s):.2f}')
    d1,d2 = { 'Name':'Gaussian' },{ 'Name':'Actual' }
    d3,d4 = { 'Name':'Delta' }, { 'Name':'Pct' }
    pct = [0.15,2.5,16,50,84,97.5,99.85]
    for i in range(7):
        sd = dfv.mean() + (i-3) * dfv.std()
        d1[str(i-3)+'z'] = sd
        d2[str(i-3)+'z'] = dfv.quantile(pct[i]/100.0)
        d3[str(i-3)+'z'] = dfv.quantile(pct[i]/100.0) - sd
        d4[str(i-3)+'z'] = 100.0 * (dfv.quantile(pct[i]/100.0) - sd)/sd
    return pd.DataFrame([d1,d2,d3,d4])

class StockGroup:
    AAA = "TSBK,MKC,OGS,CHD,PNW,SEIC,CHKP,PLBC,RHI,EBSB,XEL,YORW,ELS,GNTX,ABCB,CBAN,PAYX,ASB,MNST,MA,NVR,CSCO,BRO,PCSB,LNT,APH,MANH,EBTC,UNP,AME,ANSS,ADP,AAPL,CATY,VRSK,BK,MMM,OVLY,EXPO,BR,SFST,STT,CP,VCTR,NDAQ,FIZZ,WEC,UL,GGG,ROL,FAST,PBIP,ORCL,TBNK,JKHY"
    AA  = "AX,IDA,TD,CPK,INMD,FDS,BABA,HON,CCB,FRC,TXN,BXS,PBCT,AMGN,FMAO,BCPC,CNPAY,QLYS,PRI,GBCI,FR,TROW,EA,USNA,IEX,MAS,CTSH,BKSC,ITW,GCBC,BFC,MKTX,FCAP,UNH,CGNX,HD,IRCP,WASH,AROW,AYI,MPX,EDU,ITIC,SYBT,WBS,CHCO,ALTA,SMMF,AMZN,AMP,ICLR,RGCO,CCBG,FFIV,INTU,MSCI,FDBC,ISRG,BMO,TDY,CE,ABTX,ZTS,CVCY,CACC,CIVB,ADBE,ACN,ERIE,ROK,ANET,MPWR,BFST,PKBK,TMO,GOOG"
    A   = "REGN,ATHM,PAC,FB,JOBS,SBNY,AZPN,IX,DSGX,MTD,PAYC,RY,ESQ,MS,ESNT,HEI,SNA,BUSE,SFNC,FBHS,NICE,TYL,PNFP,KL,CCF,FAF,TRMB,HELE,COLB,PKG,CDNS,SCHW,ABMD,FTNT,NMIH,SIVB,CNBKA,NVDA,CTBI,DTE,SWX,LII"
    FOOD   = "ADM,BG,BUD,CAG,CPB,DAR,GIS,HRL,HSY,INGR,K,KHC,LW,MDLZ,POST,PPC,SJM,TAP,TSN"
    INDIA  = "HDB,INFY,WIT,TTM,CTSH,SYFY,AZRE,WNS"
    CHINA  = "BABA,JD,PDD,DIDI,BIDU,TCEHY,TAL,EDU,DQ,JKS,NIO,XPEV,VIPS"

    #too big to fail
    TBTF = "AAPL,MSFT,GOOG,GOOGL,AMZN,TSLA,NVDA,TSM,JPM,UNH,V,JNJ,META,WMT,PG,BAC,XOM,HD,MA,TM,CVX,KO,DIS,PFE,ABBV"

    SHORT  = "NOW"
    STEADY = "ACGL,BNS,PSA,JNPR,UNH,CVS,CB,BMO,AVB,PGR,KO,HSY,ICE,CME"

    WATCH    = "GOOG,META,AMZN,GLD,MSFT,INTC,AMD,NVDA,BABA,ALGN,SNOW,SLV,SHOP,TSLA,BABA,ADBE,CMCSA"
    CRYPTO   = "COIN,RIOT,SQ,PYPL,MSTR,SI,SOFI,HOOD"

    ########### OPTIONS #########
    BUFFET  = "AAPL,BAC,AXP,KO,KHC,CVX,KO,OXY,MCO,ATVI,HPQ"
    PMCC    = "ORCL,PFZR,MA,V,COST,AMD,F,COP,VZ,DVN,NVDA,WMT,ACHC" + "," + BUFFET
    CONDOR  = "SPY,IWM,XLP,COST,FXI,EEM,KWEB,BABA,XLV,MRK,QQQ,MSFT,AAPL,XLK,ORCL,IBM,GOOG,XLE,USO,XOP,GLD,GDX,SLV,AXP,GS,DE,CAT,AMZN"

    ######## Always updates and changes ############
    ### Prices 250,120,80,110
    VALUE   = "META,TGT,DIS,GOOG"
    CRUSHED = "SHOP,TSLA,NFLX,NVDA,PYPL,META,AMD,INTC,BABA,PLTR,ROKU,SNOW,OKTA,SQ,DASH"
    ACTIVE  = "CMCSA,MU,NVDA,GRMN,IBM,PYPL,V,FIS,GLD,NIKE,SNOW,AB,MORN,TW,PING,CBRE,EBAY,ALLY,COF,DOCU,TROW,TGT,AMEX,ORCL,GRMN,SHOP,ADBE,CRM"
    ARKK    = "TDOC,DOCU,DKNG,TSLA,ZOOM,ROKU,COIN,SPOT,TWLO,EXAS,SHOP,U,PATH"
    MW      = "PDD,BIDU,JD,MELI,CZR,GNRC,ALK,PYPL,CRWD,TCOM,TMUS,ENPH,GPN,NTES,ATVI,LUV,FIS,MTCH,LDOS,WRK,MDT,TFX,ZBH,PTC,PSX,BA,OKTA,DIS,GLW,LW"
    EVMO    = "V,RACE,LUV,GNRC,JNJ,UNH,XOM,PG,LLY"

    class Value:
        ENERGY        = 'XOM,COP,DVN'
        TECH          = 'META,HPQ,INTC,T,GOOG'
        FINANCE       = 'CB,AGO,ITUB'
        HEALTH        = 'LLY,REGN,CF,UNH'
        TRADE         = 'MCK,CAR'
        REIT          = 'PSA,MAR,WELL'
        MANUFACTURING = 'NUE,HSY,BG,KO'

        @classmethod
        def all(clz):
            return clz.ENERGY + "," + clz.TECH + "," + clz.FINANCE + "," + \
                clz.HEALTH + "," + clz.TRADE + "," + clz.REIT + "," + clz.MANUFACTURING

    @staticmethod
    def lot(num):
        if num == 0:  return StockGroup.Value.all()
        elif num == 1:  return "MSFT,GOOG,AAPL,AMZN,FB" #NFLX,TSLA
        elif num == 2:  return StockGroup.Etf.COMMODITY
        elif num == 3:  return "T,PGR,INTU,MET" #ESSENTIALS
        elif num == 4:  return StockGroup.Finance.BANK
        elif num == 5:  return StockGroup.Tech.SEMI
        elif num == 6:  return "SMIN,EPI"
        elif num == 7:  return "VGK,FEZ"
        elif num == 8:  return "DXJ,HEWJ"
        elif num == 9:  return StockGroup.Retail.STORE
        elif num == 10: return StockGroup.Retail.ECOM
        elif num == 11: return StockGroup.Energy.EV
        elif num == 12: return StockGroup.Energy.AIR
        elif num == 13: return StockGroup.Home.BUILDERS
        elif num == 14: return StockGroup.Health()
        #elif num == 13: return Stocks.ENERGY.AIR #HYPES
        #elif num == 14: return Stocks.ENERGY.AIR  #DIPS

    class Finance:
        EXCH      = "ICE,CME,MKTX,CBOE,NDAQ,SPGI"
        BANK      = "GS,FRC,BAC,WFC,C,JPM,AXP,V,COF,MA,ALLY,USB,MS,PNC,BK,UBS,BCS,HSBC"
        CREDIT    = "AIG,TRU,EFX,INFO,NLSN"
        INSURANCE = "PGR,MET"

    class Health:
        DRUGS     = "JNJ,PFE,MRNA"
        INSURANCE = "AET,ANTM,UNH,HUM,CI"
        STORES    = "CVS,WBA"
        DEVICES   = "ALGN,PG"

        @classmethod
        def all(clz):
            return clz.DRUGS + "," + clz.INSURANCE + "," + clz.STORES + "," + clz.DEVICES

    class Energy:
        OIL   = "NEE,COP,TRP,PTR,EC,VLO,XOM,CVX,BP,EOG,SU,NRG,WES"
        AIR   = "AAL,DAL,LUV,JBLU,UAL,SAVE,ALK,BA,ALGT,SKYW"
        AUTO  = "GM,FORD,TTM,TM,HMC"
        EV    = "TSLA,NIO,LCID,RIVN,XPEV,LI"

    class Retail:
        ECOM     = "SHOP,AMZN,SQ,EBAY,PYPL,MELI,SE,AFRM,JD,CPNG,CVNA"
        STORE    = "TGT,BURL,ROST,DG,COST,WMT,BIG,DLTR,SBUX,FIVE,BBY"
        SERVICES = "GRUB,ETSY,DASH,ABNB,OWW,EXPE,TRIP,YELP,SQ,UBER,LYFT"

    class Home:
        BUILDERS = "LOW,HD,W,KBH,TOL,PHM,LEN,RDFN,ZG"

    class Etf:
        # XSD - Semi, XLE - Energy, XLV - Health
        # XLF - Finance, VNQ, XLRE - REIT, XLP - Staples
        STOCK     = "SPY,QQQ,IWM,XSD,XLV,XLF,XLP,XLE,XLRE,VNQ,GDX,XLK,XLC,XLB,XLI,XLY,KRE,KBE"
        VOL       = "VXX,VXY"
        COMMODITY = "GLD,SLV,USO,CPER,BNO,WEAT,SOYB,RJA,RJI,BITO"

        @classmethod
        def all(clz):
            return clz.STOCK + "," + clz.VOL + "," + clz.COMMODITY

    class Tech:
        SEMI      = "ANET,AMAT,TXN,NVDA,MU,AMD,INTC,AMAT,RBLX,QCOM,TSM"
        OUTSOURCE = "CTSH,INFY,IT,ACN,FISV,IBM"
        FANG      = "FB,GOOG,GOOGL,MSFT,AMZN,AAPL,NFLX,TSLA"
        MEDIA     = "FB,TWTR,GOOG,NFLX"

    @staticmethod
    def help():
        print("CRUSHED,ACTIVE,CHINA,ARK,MW,FOOD,INDIA")
        print("FINANCE\t\tEXCH,BANK,CREDIT,INSURANCE")
        print("HEALTH\t\tDRUGS,INSURANCE,STORES,DEVICES")
        print("ENERGY\t\tOIL,AIR,AUTO,EV")
        print("RETAIL\t\tECOM,STORE,SERVICES")
        print("HOME\t\tBUILDERS")
        print("ETF\t\tSTOCK,VOL,COMMODITY")
        print("TECH\t\tSEMI,OUTSOURCE,FANG,MEDIA")

def option_delta(type,lastpx,vol,stkpxincr):
    days = []
    if stkpxincr == 0:
        if lastpx < 100: stkpxincr = 1
        elif lastpx < 500: stkpxincr = 2.5
        elif lastpx < 1000: stkpxincr = 5
        else : stkpxincr = 10
    vol = round(vol,2)
    print("Last:", lastpx, "Vol:", "{0:.3f}".format(vol),"Incr:",stkpxincr)

    stks = [round(lastpx,2)]
    st = (lastpx//stkpxincr)*stkpxincr - 19*stkpxincr
    for idx in range(40): stks.append(round(st + idx * stkpxincr,2))
    stks.sort()

    for stkpx in stks:
        px = [stkpx,vol]
        for day in [0,7,15,30,45,60,91,182,365,730,1095]:
            px.append(round(single_option_delta(type,lastpx,vol,day,stkpx,False),2))
            #px.append(Option(european=False, kind=type, \
            #        s0=lastpx, k=stkpx, r=0.001, t=day, dv=0, \
            #        sigma=vol).getPrice(method='BT',iteration=100))
        days.append(px)
    return pd.DataFrame(days,columns='stk vol exp wk 2wk mth 6wk 2mth 3mth 6mth yr 2yr 3yr'.split())

#prices = -25% to +25% in 2.5% incr
#days = 7, 30, 91, 182, 365
def option_pricer(type,lastpx,vol,stkpxincr):
    days = []
    if stkpxincr == 0:
        if lastpx < 100: stkpxincr = 1
        elif lastpx < 500: stkpxincr = 2.5
        elif lastpx < 1000: stkpxincr = 5
        else : stkpxincr = 10
    vol = round(vol,2)
    print("Last:", lastpx, "Vol:", "{0:.3f}".format(vol),"Incr:",stkpxincr)

    stks = [round(lastpx,2)]
    st = (lastpx//stkpxincr)*stkpxincr - 19*stkpxincr
    for idx in range(40): stks.append(round(st + idx * stkpxincr,2))
    stks.sort()

    for stkpx in stks:
        px = [stkpx,vol]
        for day in [0,7,15,30,45,60,91,182,365,730,1095]:
            px.append(round(single_option_pricer(type,lastpx,vol,day,stkpx,False),2))
            #px.append(Option(european=False, kind=type, \
            #        s0=lastpx, k=stkpx, r=0.001, t=day, dv=0, \
            #        sigma=vol).getPrice(method='BT',iteration=100))
        days.append(px)
    return pd.DataFrame(days,columns='stk vol exp wk 2wk mth 6wk 2mth 3mth 6mth yr 2yr 3yr'.split())

def single_option_pricer(type, lastpx, vol, days, strike, trace=True):
    if trace:
        print("Last:", lastpx, "Vol:", "{0:.3f}".format(vol), "strike:", strike, "days:", days)
    if days <= 0:
        if lastpx > strike:
            return lastpx - strike if type == 'call' else 0
        else:
            return strike - lastpx if type == 'put' else 0

    return Option(european=False, kind=type, s0=lastpx, k=strike, r=0.001, \
            t=days, dv=0, sigma=vol).getPrice(method='BT',iteration=100)

def single_option_delta(type, lastpx, vol, days, strike, trace=True):
    p1 = single_option_pricer(type, lastpx*1.01, vol, days, strike, trace)
    p2 = single_option_pricer(type, lastpx*0.99, vol, days, strike, trace)
    return (p1-p2)/(0.02 * lastpx)

def query(cols, df, qry, sort, asc):
    print("Query:",qry,sort,asc)
    df1 = df if qry is None else df.query(qry)
    if len(sort.split(",")) == 1:
        df1 = df1.loc[df1.eval(sort).sort_values(ascending=asc).index]
    else:
        df1 = df1.sort_values(sort.split(","))
    return df1 if cols is None else df1[cols]

def matchsymbol(name):
    df = ss.df[ss.df.name.str.match(name,case=False)]
    return list(df.ticker.values)

class Symbol:
    def __init__(self,ctx):
        for s in ctx.df.ticker:
            setattr(Symbol, s.lower(), s)

class Mode:
    all = 'ALL'
    nowatch = 'NOWATCH'
    watch = 'WATCH'
    large = 'LARGE'
    small = 'SMALL'
    option = 'OPTION'

class screens:

    def __init__(self,ss):
        self.ss = ss

    def __call__(self):
        print("pe()       - around 5-20 pe")
        print("corr()     - low downcorr but healthy qoq yoy growth")
        print("dips(wzscr)  - significant week OR month drop on overall good cagr stocks ")
        print("risk(type) - low risk, steady stocks ")
        print("blazers()  - momentum stocks scaled new peaks, low downtick freq")
        print("rebounds() - rebounding to catch old peaks")
        print("soldoffs() - Stocks with low rangescore")
        print("laggards(score)", "Biggest laggers w.r.t SPY,QQQ,IWM")
        print("leaders(score)", "Biggest beaters w.r.t SPY,QQQ,IWM")
        print("lowtailrisk()", "Low tail risk picks for credit spreads")

    def rebounds(self):
        qry = "(mktcap > 20 and qpeak > 20 and mompnl > 3)"
        print("\t\t\t\t\t\t\t\t\t\t\t Stocks rebounding to catch up to its previous peak " + qry)
        print("Rating ****")
        return query(cols(self.ss.type), self.ss.df, qry, "mompnl", False).head(50)

    def blazers(self):
        print("Rating **** \t\t\t Scaled new peak and no vol ")
        qry = "(mktcap > 20 and qpeak > 15 and dtpm < 12)"
        return query(cols(self.ss.type,'qpeak'), self.ss.df, qry, "mktcap", False).sort_values("sect").head(50)

    def soldoffs(self):
        qry = "(rangescore < 20 and mktcap > 20)"
        print("Rating *")
        return self.ss.df.query(qry).sort_values(["sect","mktcap"],ascending=[False,False])[cols(self.ss.type)]

    # def model(self):
    #     print("Model predicted")
    #     return self.ss.query("label == 1","pred,label").sort_values(['sect','mktcap'],ascending=[False,False])
    def laggards(self, lag=20):
        print("List of large caps that lag qqq and spy lot in prscore")
        score = self.ss.query("ticker in ('QQQ','SPY','IwM')").prscore.mean()
        qry = "mktcap > 10 and prscore < " + str(score - lag)
        return self.ss.query(qry).sort_values('prscore')

    def leaders(self, lead=20):
        print("List of large caps that lag qqq and spy lot")
        score = self.ss.query("ticker in ('QQQ','SPY','IwM')").prscore.mean()
        qry = "mktcap > 10 and prscore > " + str(score + lead)
        return self.ss.query(qry).sort_values('prscore')

    def buys(self):
        qry = "(pe > 0 and pe < 20 and qdrop > 15)"
        return self.ss.query(x='qdrop',y=qry,sort='qdrop').sort_values(['sect','qdrop'])

    def pe(self):
        print("Not overvalued (pe < 20 sortby mktcap)")
        print("Rating *")
        return self.ss.df.query("pe > 5 and pe < 20")[cols(self.ss.type)].sort_values("mktcap",ascending=False)

    def corr(self):
        qry = "(mktcap > 20 and downcorr <= 0.2 and ema60 < 100 and ema200 < 100)"
        print("Low downcorr but growing in yoy and qoq " + qry)
        print("Rating ***")
        return self.ss.df.query(qry).sort_values("sect")[cols(self.ss.type,"ema60")]

    def lowtailrisk(self):
        print("Low tailrisk stocks ")
        return self.ss.squery("bumpiness < 10 and trisktouch < 8 and lastpx > 50 and (mktcap > 20 or mktcap == 0)",'histvol,volscore,sect',sort='abs(histvol-optvol)').head(50)

    def volchgopt(self, sort='diff', val=0.01):
        self.ss.mode(Mode.option)
        return self.volchg(sort, val)

    def volchg(self, sort='diff', val=0.01):
        ''' diff is spot to avg difference, dir = time-weighted-spot-vol to simple-spot-vol '''
        df = self.ss.squery("mktcap == 0 or mktcap >= 10","optivol,histvol,volscore,bumpiness,trisktouch,triskbreach,corr,sect").copy()
        self.ss.mode()
        df['diff'] = df.optvol - df.histvol
        df['dir']  = df.optivol - df.optvol
        if val > 0: df = df.query(sort + " > " + str(val))
        else: df = df.query(sort + " < " + str(val))
        return df.sort_values(['sect',sort],ascending=[True,val < 0])

    def steady(self, type = 0):
        sort = "sect qoqpnl"
        y = ""
        x = ""
        if type == 0:
            x = "dtpm,stlpm,qoqpnl"
            y = "(stlpm <= 2 and dtpm <= 11 and qoqpnl-mompnl > 5 and yoypnl-qoqpnl > 18)"
        if type == 1:
            x = "bumpiness,qoqpnl"
            y = "bumpiness < 5 and yoypnl > 10 and qdrop + qrebound < 10"
        if type == 2:
            x = "qoqpnl"
            y = "(yoypnl-qoqpnl)/9 > 1 and (qoqpnl-mompnl)/2 > 1 and mompnl > 1 "
        return self.ss.query(y=y,x=x).sort_values(sort.split(),ascending=False)

    def dips(self,dropsd=-1):
        qry = "weekdip < "+str(dropsd)+" and mompnl - weekpnl < 5 and mktcap > 20"
        print("short term drops", qry)
        print("Rating ***")
        return self.ss.query(y=qry, sort="-1*weekdip")

    ######################################### Displayed ###########

    def liquid(self):
        return self.ss.watch(optstocks())

    def nextweekpnl(self):
        # CHANGED: legacy read /data/pred_final.csv from local disk; no backend op exposes the
        # model output, so every ticker gets nextweekpnl = 0 (the legacy fallback value).
        df = self.ss.df[['ticker']].copy()
        #df = predict_model(fmod, data=df)
        #df.rename(columns={'Label': 'nextweekpnl'}, inplace=True)
        if 'nextweekpnl' not in df.columns:
            df['nextweekpnl'] = 0
        df = df["ticker nextweekpnl".split()]
        df1 = self.ss.squery("mktcap >= 0")
        df = pd.merge(df,df1,how='inner',on='ticker').sort_values('nextweekpnl')
        return df

    def etfs(self):
        qry = "(mktcap == 0)"
        return self.ss.squery(qry,"-mompnl")

    def condor(self):
        ## Only etfs or largecaps ##
        qry  = "impvol > 25 and impvol < 70 and lastpx > 50 and earnings > 45 "
        qry += " and impvol - triskvol > -10 "
        return self.ss.squery(y=qry,x='sect,prscore',sort="mktcap")

    def callspread(self):
        ## volratio checks how directional stocks have been ##
        qry = "(mktcap == 0 or mktcap > 10) and volratio > 1.25 "
        qry += " and (rangescore > 90 or prscore > 90)  and (optivol - optvol) < 10"
        return self.ss.squery(y=qry,x='sect,prscore',sort="histvol/volratio")

    def putspread(self):
        ## volratio checks how directional stocks have been ##
        qry = "(mktcap == 0 or mktcap > 10) and volratio > 1.25 "
        qry += " and (rangescore < 10 or prscore < 10) and (optivol - optvol) < 10"
        return self.ss.squery(y=qry,x='sect,prscore',sort="histvol/volratio")

    def momentum(self):
        print("Use dpnl for momentum with consistency and sort by r2")
        return self.ss.squery(y="dpnl > 0 and mktcap > 5",x="r2,range,corr,sect",sort="dpnl*r2").head(50).sort_values(['sect','dpnl'],ascending=False)
        #return self.ss.squery(y="dpnl > -5 and mktcap > 5",x="r2,range,corr",sort="dpnl*r2")

    def lowlongvol(self):
        print("Best stocks by sector with below expected longvol")
        filter = "volratio < 0.7 and mktcap > 10 and lastpx > 100"
        df = self.ss.squery(filter, x="weekpnl,mompnl,corr,sect").sort_values(['sect','histvol'],ascending=False).groupby('sect')
        return df.head(abs(5)).reset_index(drop=True).sort_values(['sect','histvol'],ascending=False)

class webs:

    def __init__(self,ss):
        self.ss = ss

    def __test__(self, url):
       # CHANGED: no outbound HTTP probe from the frontend; assume the URL exists.
       return True

    def __process__(self, url, bopen):
        # CHANGED: the browser is opened only in the IPython shell (legacy.shell sets OPEN_BROWSER);
        # inside the Dash server the URL is just returned.
        if bopen and OPEN_BROWSER:
            import webbrowser as wb
            wb.open(url, new=0)
        return url

    def macro(self, tick, bopen = True):
        url = "https://en.macromicro.me/collections/34/us-stock-relative/24033/wilshire5000-to-us-m2"
        return self.__process__(url, bopen)

    def finviz(self, tick, bopen = True):
        return self.__process__("https://finviz.com/screener.ashx?v=111&ft=4", bopen)

    def dataroma(self, tick, bopen = True):
        return self.__process__("https://www.dataroma.com/m/home.php", bopen)

    def valuesider(self, tick, bopen = True):
        return self.__process__("https://valuesider.com/", bopen)

    def ostrat(self, tick, bopen = True):
       return self.__process__("https://optionstrat.com/", bopen)

    # yahoo finance is default
    def __call__(self, tick):
        self.yhoo(tick)

    def barchart(self, tick, bopen = True):
        return self.__process__("https://www.barchart.com/stocks/quotes/" + str(tick) + "/overview", bopen)

    def mktchemleon(self, tick, bopen = True):
        return self.__process__("https://marketchameleon.com/Overview/" + str(tick) + "/IV", bopen)

    def zacks(self, tick, bopen = True):
       return self.__process__("https://www.zacks.com/stock/chart/" + str(tick) + "/fundamental/price", bopen)

    def morningstar(self, tick, bopen = True):
       url = "https://www.morningstar.com/stocks/xnys/" + str(tick) + "/valuation"
       if self.__test__(url) == False:
           url = "https://www.morningstar.com/stocks/xnas/" + str(tick) + "/valuation"
       return self.__process__(url, bopen)

    # yahoo finance is default
    def yahoo(self, tick, bopen = True):
        return self.__process__("https://finance.yahoo.com/quote/" + str(tick), bopen)

    def yhoption(self, tick, bopen = True):
        return self.__process__("https://finance.yahoo.com/quote/{0}/options?p={0}".format(str(tick)), bopen)

    def tipranks(self, tick, bopen = True):
       return self.__process__("https://www.tipranks.com/stocks/" + str(tick), bopen)

    def chartmill(self, tick, bopen = True):
       return self.__process__("https://www.chartmill.com/stock/quote/" + str(tick), bopen)

    def fidoption(self,tick, bopen = True):
       return self.__process__("https://researchtools.fidelity.com/ftgw/mloptions/goto/optionChain?symbol=" + str(tick), bopen)

class mktsummary:

    def __init__(self,ss):
        self.ss = ss

    def __call__(self, types = 'sect', metric = 'daypnl'):
        self.summary(types,metric)

    def desc(self, featexpr, qry, group = None):
        if group == None:
            return describe(self.ss.df, featexpr, qry)
        else:
            indf = self.ss.df.query(qry)
            df = indf.groupby(group.split(','))[featexpr].agg(
                ['min',('p5',lambda df:df.quantile(0.05)), 'median',('p95',lambda df:df.quantile(0.95)),
                'max','count','mean','std']).query("count > 1").sort_values('mean').reset_index()
            return df

    def summary(self, types = 'sect', metric = 'daypnl', qry = "mktcap > 0"):
        print('Segment by type=[sect,ind,petype,cross,mktsize] and summarize metric ')
        indf = self.ss.df.query(qry)
        df = indf.groupby(types.split(','))[metric].agg(
            ['min',('p5',lambda df:df.quantile(0.05)), 'median',('p95',lambda df:df.quantile(0.95)),
            'max','count','mean']).query("count > 1").sort_values('mean').reset_index()
        title = metric + " summary by " + types
        #print(df)
        return graph.hbar_group(df, types, "p5,p95,median,mean", tval=title), df

    def groupsummary(self, grpmetric, metric):
        print('Segment by grpmetric and summarize metric ')
        ''' auto-segments by grpmetric and summarizes metric by the segments '''
        indf = self.ss.df[[grpmetric, metric]].copy()
        def tfunc(val,darr,arr):
            res = 0
            for a in darr:
                if val > a:
                    res = res+1
            return arr[res]
        darr = []
        arr = [0,2.5,16,50,84,97.5,100]
        for p in arr: darr.append(indf[grpmetric].quantile(p/100))
        print(pd.DataFrame({'arr': arr, 'val': darr}))

        darr = []
        for p in [2.5,16,50,84,97.5]: darr.append(indf[grpmetric].quantile(p/100))
        arr = "-3z,-2z,-1z,+1z,+2z,+3z".split(",")
        indf['grp'] = indf.apply(lambda row: tfunc(row[grpmetric],darr,arr),axis=1)
        return self.summary('grp', metric, indf)

    def mktsummary(self, qry = "mktcap > 0", rpt = None):
        if rpt is None:
            print(summary_perf(self.ss.ctx.df.query(qry)))
            print(summary_trend(self.ss.ctx.df.query(qry)))
        if rpt == 'trend':
            return summary_trend(self.ss.ctx.df.query(qry))
        if rpt == 'perf':
            return summary_perf(self.ss.ctx.df.query(qry))

    def portfolio(self, type='MW'):
        return self.ss.ctx.portfolio(type).sort_values(["mompnl"])

    def sector(self, metric='weekpnl'):
        return self.summary('sect', metric)

    def mktsize(self, metric='weekpnl'):
        return self.summary('mktsize', metric)

    def industry(self, metric='weekpnl'):
        return self.summary('ind', metric)

    def topn(self, group = 'sect', metric = 'mktcap', top=5, filter = "mktcap >= 0"):
        print("Top ", top, " stocks by ", metric, " where ", filter)
        df = self.ss.squery(filter, group+","+metric).sort_values(metric,ascending=(top<0)).groupby(group)
        return df.head(abs(top)).reset_index(drop=True).sort_values([group,metric],ascending=[True,top<0])

    def yymetric(self, tick, metric = 'MKTPX'):
        tick = tick.upper()
        metric = metric.upper()
        df = tsmetricsdata(tick, metric)
        df['dvalue'] = df[tick+'/'+metric]
        if metric == 'MKTPX':
            p = self.ss.df.query("ticker == @tick").lastpx.max()/100.0
            df['dvalue'] = df[tick+'/'+metric] * p
        df['day'] = df['asof'].dt.day
        df['yymm'] = df['asof'].dt.year * 100 + df['asof'].dt.month
        return df.tail(395).pivot(index='day',columns='yymm',values='dvalue')

class stkgraph:

    def __init__(self,ss):
        self.ss = ss

    def __call__(self,stk):
        self.tsstock(stk)

    def distribution(self, featexpr, qry = "mktcap >= 0"):
        return distribution(self.ss.df, featexpr, qry)

    def heat(self, feat, qry = "mktcap >= 0"):
        return heat(self.ss.df.query(qry), feat)

    def tailrisk(self, stk, vol = 0, fdelta=35):
        o = self.ss('opricer')(stk)
        if (vol > 0): o.set(0,vol)
        df = optcheck(stk, ovar(o.entry.last, o.entry.vol, 60), fdelta, tailrisk)
        return graph.line(df,'days','tup,tdown,touch,xdown,xup', "Tailrisk for stock " + stk)

    def month_ohlc(self, stk, vol = 0, fdelta=35, days = 60):
        df = self.ss('yymetric',stk,'mktpx')
        df = pd.concat([df.groupby(lambda x: 'low').min(),
            df.groupby(lambda x: 'open').first(),
            df.groupby(lambda x: 'close').last(),
            df.groupby(lambda x: 'high').max()]).transpose().reset_index()
        df['yymm'] = df.yymm.astype(str)
        df['yymm'] = df['yymm'].str.slice(2,4) + '/' + df['yymm'].str.slice(4,6)
        return graph.bar_group(df,'yymm','open,low,high,close', "Monthly OHLC for stock " + stk, rangescale=True)


    def cone(self, stk, vol = 0, fdelta=35, days = 60):
        df = optcheck(stk, ovar(0, vol, days), fdelta, expectedspread)
        return graph.line(df,'asof','lastpx,epxlow,epx,epxhigh,sprlow,sprhigh', "Expected move for the stock " + stk)

    #def cbscore(self, stk):
    #    ''' returns consolidation-breakout score as timeseries for a given timewindow.
    #        100 * (pxchgabs-minpxchgabs/maxpxchgabs-minpxchgabs) = 0-100   '''

    def swingrange(self, stks):
        ''' returns swing% as timewindow in days expands '''
        stks = stks.upper()
        dict = {"days": list(range(7,100,7))}
        for stk in stks.split(","):
            p = self.ss.df.query("ticker == @stk").lastpx.max()/100.0
            df = tsmetricsdata(stk, "MKTPX", 20211201)
            df['lastpx'] = df[stk+"/MKTPX"] * p
            df = df.tail(365)
            arr = []
            for i in range(7,100,7):
                fact = 1 #math.sqrt(365/i)
                df['range'] = fact * 100 * (df.lastpx.rolling(i).max() - df.lastpx.rolling(i).min()) / df.lastpx.rolling(i).mean()
                arr.append(df.tail(180).range.mean())
            dict[stk] = arr
        df = pd.DataFrame(dict)
        return graph.line(df,'days',stks, "Expected swing for the stock " + stk)

    def swing(self, stk, days=45):
        ''' returns swing% for given timewindow as timeseries '''
        stk = stk.upper()
        p = self.ss.df.query("ticker == @stk").lastpx.max()/100.0
        df = tsmetricsdata(stk, "MKTPX", 20211201)
        df['lastpx'] = df[stk+"/MKTPX"] * p
        df['range'] = 100 * (df.lastpx.rolling(days).max() - df.lastpx.rolling(days).min()) / df.lastpx.rolling(days).mean()
        df = df.tail(180)
        return graph.line(df,'asof','range', "Expected swing for the stock " + stk)

    def tsdrawdown(self, stks, dte = 360):
        stks = stks.upper()
        dfout = None
        for stk in stks.split(","):
            df = readohlc(stk)
            df['maxu'] = df.close.rolling(365).max()
            df[stk] = 100.0 * (df.maxu-df.close) / df.maxu
            df = df.tail(dte)
            df = df[['asof',stk]]
            if dfout is None: dfout = df
            else: dfout = pd.merge(dfout, df, on='asof')
            #dfout = pd.merge(df, dfout, on='asof', how='left')
        return graph.line(dfout,'asof',stks, "Drawdown for the stock " + stk)

    def tsdrawdowndist(self, stk):
        df = readohlc(stk)
        df['maxu'] = df.close.rolling(365).max()
        df[stk] = 100.0 * (df.maxu-df.close) / df.maxu
        df = df.tail(720)
        df = df[['asof',stk]]
        return distribution(df,stk)


    def bband(self, stk):
        p = self.ss.df.query("ticker == @stk").lastpx.max()/100.0
        df = tsmetricsdata(stk, "MKTPX", 20211201)
        df['lastpx'] = df[stk+"/MKTPX"] * p
        df['mvavg'] = df.lastpx.rolling(21).mean()
        df['mvstd'] = df.lastpx.rolling(21).std()
        df['bbhigh'] = df['mvavg'] + 2 * df['mvstd']
        df['bblow'] = df['mvavg'] - 2 * df['mvstd']
        df['bbsize'] = 4 * df['mvstd']
        df = df.tail(180)
        return graph.line(df, 'asof', "lastpx,mvavg,bbhigh,bblow,bbsize", "BB for ticker = " + stk)

    def rsi(self, stk):
        stk = stk.upper()
        p = self.ss.df.query("ticker == @stk").lastpx.max()/100.0
        df = tsmetricsdata(stk, "MKTPX,RSI", 20211201)
        csv = []
        for v in df.columns.values:
            if v != 'asof':
                csv.append(v)
        df = df.tail(180)
        return graph.line(df, 'asof', ",".join(csv), "RSI for ticker = " + stk)

    def pxdist(self, stk):
        p = self.ss.df.query("ticker == @stk").lastpx.max()/100.0
        df = tsmetricsdata(stk, "MKTPX", reldate(-180))
        df['close'] = df[stk+"/MKTPX"] * p
        dfv = df.eval('close')
        mu = dfv.agg('mean')
        sd = dfv.agg('std')
        s = np.random.normal(mu, sd, 10*len(dfv))
        dfw = pd.DataFrame({"gaussian": s, "actual": 10*dfv.to_list()})
        return graph.hist(dfw,"gaussian,actual")

    def macd(self, stk):
        df = tsmetricsdata(stk, "MKTPX,EMA20,EMA60,EMA200", 20220101)
        if len(df) == 0:
            print("No data")
            return
        #df[stk+"/E20VAR"] = df[stk+ "/MKTPX"] - df[stk+"/EMA20"]
        csv = []
        for v in df.columns.values:
            if v != 'asof':
                csv.append(v)
        df = df.tail(720)
        return graph.line(df, 'asof', ",".join(csv), "MACD for ticker = " + stk)

    def mvavg(self,stk, win=30):
        ''' Timeseries on price trends before/after pandemic '''
        df = loadts(stk)
        df['corona'] = df['adjclose']
        df.loc[df['asof'] < pd.to_datetime('20200101'),'corona']=None
        return graph.mvavg(df,'asof','adjclose,corona', win, "Trend for stock " + stk)

    def scatter(self, tkr, ref='SPY'):
        ''' Timeseries on Daily returns and intraday spreads '''
        df = qrypdb(tkr)
        df[tkr.upper()] = df['adjclose']/100.0
        df1 = qrypdb(ref)
        df1[ref.upper()] = df1['adjclose']/100.0
        df = pd.merge(df,df1,on='asof',how='inner').sort_values('asof')
        return graph.scatter(df,ref.upper(),tkr.upper(), "Corr stock " + tkr + " vs " + ref)

    def drscatter(self, tkr, ref='SPY'):
        ''' Timeseries on Daily returns and intraday spreads '''
        df = qrypdb(tkr)
        df[tkr.upper()] = 100 * df.adjclose.diff()/df['adjclose']
        df1 = qrypdb(ref)
        df1[ref.upper()] = 100 * df1.adjclose.diff()/df1['adjclose']
        df = pd.merge(df,df1,on='asof',how='inner').sort_values('asof')
        df = df.head(-1)
        return graph.scatter(df,ref.upper(),tkr.upper(), "DR Corr stock " + tkr + " vs " + ref)

    def drtrend(self,tkr):
        ''' Timeseries on Daily returns and intraday spreads '''
        df = qrypdb(tkr)
        df = df.sort_values('asof')
        df['asof'] = pd.to_datetime(df['asof'].map(str),format='%Y%m%d')
        df['dr'] = 100 * df.adjclose.diff()/df['adjclose']
        df['spr'] = 200 * (df.high - df.low)/(df.open + df.close)
        return graph.trend(df,'asof','dr,spr', "Daily return Trend for stock " + tkr)

    def drfreq(self,tkr):
        ''' Timeseries on Daily returns and intraday spreads '''
        print("Dstribution of dailychg% for last 100 days")
        df = qrypdb(tkr)
        df = df.sort_values('asof')
        df['asof'] = pd.to_datetime(df['asof'].map(str),format='%Y%m%d')
        df['lower'] = round(200 * df.adjclose.diff()/df['adjclose'])*0.5
        df = df.groupby('lower')['ticker'].count().reset_index()
        df['higher'] = df['lower']+0.5
        df['cumcnt'] = df.ticker.cumsum()
        df['cnt'] = df.ticker
        return df[['lower','higher','cnt','cumcnt']]

    def tsvoltime(self, stks):
        df = tsmetricsdata(stks, "MKTPX", 20210101)
        arr = stks.split(",")
        tsdf = pd.DataFrame()
        for idx in range(365):
            days = idx + 1
            dict = {"days":days}
            for stk in arr:
                df['dr'] = 100 * df[stk+"/MKTPX"].diff(periods=days)/df[stk+"/MKTPX"]
                # annualized stdev(pct_mov_n_days)
                dict[stk] = df.tail(365).dr.std() * pow(365/days,0.5)
                #vol = spr * pow(365/days,0.5) / 6 * px
            tsdf = append_row(tsdf,dict)
        return graph.line(tsdf, 'days', stks, "Vol over duration for ticker = " + stk)
        #return tsdf.query("index % 5 == 0 and index <= 50")

    def tsvolgrowth(self, stk, days = 365):
        df = tsmetricsdata(stk, "OPTVOL,OPTIVOL", reldate(-1*days))
        if len(df) == 0:
            print("No data")
            return
        df[stk+"/GROWTH"] = df[stk+ "/OPTIVOL"] - df[stk+"/OPTVOL"]
        stkdf = self.ss.df[self.ss.df.ticker==stk.upper()]
        df[stk+"/AVGVOL"] = stkdf.histvol.max()
        df[stk+"/IVOL30"]  = qryspot(stk).spotvol.max()
        csv = []
        for v in df.columns.values:
            if v != 'asof':
                csv.append(v)
        return graph.line(df, 'asof', ",".join(csv), "Volatilities for ticker = " + stk)

    def ohlc(self, tick, days=365):
        df = readohlc(tick).tail(days)
        df['low'] = np.minimum(df.low,df.prev)
        df['high'] = np.maximum(df.high,df.prev)
        df['atr'] = df.high - df.low
        df['atr%'] = 100 * (df.close - df.low)/df.atr
        df['dr'] = 100 * (df.close - df.prev)/df.prev
        df['mvdr'] = df['dr'].rolling(7).mean()
        #df = df["asof,low,close,high".split(",")]
        #return graph.line(df, 'asof', "low,high,close", "OHLC Values for " + tick)
        return df

    def tsgrowth(self, ticks = "SPY,QQQ,IWM,XLV,XLF,XLP,XLE,XLRE,VNQ,XLB", days = 365):
        ''' Timeseries on standardized price along with etf(s) for compare '''
        #ticks = tick.upper().split(",")
        #sym = tick + ",SPY,QQQ,IWM," +
        df = self.ss.ctx.df
        sym = ticks.upper() #+ ",SPY,QQQ,IWM"
        #if len(ticks.split(",")) == 1:
        #    sym += "," + sect_etfs[self.ss.df.query("ticker == @ticks").sect.iloc[0]]
        stks = set()
        for stk in sym.split(","):
            qual = 'MW.' if df[df.ticker==stk.upper()].empty else 'STK.'
            stks.add(qual + stk)
        return timeseries(",".join(stks), reldate(-1*days))

    def tsstock(self,stk, days=9999):
        ''' Timeseries on real price with pre/post covid trends '''
        df = loadts(stk)
        df['corona'] = df['adjclose']
        df.loc[df['asof'] < pd.to_datetime('20200101'),'corona']=None
        df = df[df['asof'] > pd.to_datetime(str(reldate(-1*days)))]
        return graph.trend(df,'asof','adjclose,corona', "Trend for stock " + stk)

    def tsmetrics(self, stk, metric, days = 365):
        return tsmetrics(stk,metric,reldate(-1*days))

    def tsmktshare(self, ports='TECH,FINANCE,TRADE,HEALTH,ENERGY,MANUFACTURING,REIT'):
        return mktshare(ports)

    def tsmktvalue(self, ports='MKT,TECH,FINANCE,TRADE,HEALTH,ENERGY,MANUFACTURING,REIT'):
        return totalmkt(ports)

    def ffe(self, tkr):
        df = self.ss.ctx.df
        ffe(tkr,'MW' if df[df.ticker==tkr.upper()].empty else 'STK')

class singlestock:
    ''' Singlestock plan '''
    def __init__(self,ss):
        self.ss = ss
        self.currsym = 'SPY'

    def like(self, tick=None):
        tick = self.currsym if tick is None else tick.upper()
        se = self.ss.df[self.ss.df.ticker == tick.upper()].sect.iloc[0]
        mk = self.ss.df[self.ss.df.ticker == tick.upper()].mktcap.iloc[0]
        ind = self.ss.df[self.ss.df.ticker == tick.upper()].ind.iloc[0]
        #print(se,mk,ind)
        df = self.ss.df.query("sect == @se and ind == @ind and mktcap > 0.5 * @mk and mktcap < 2 * @mk",engine="python")
        return query(cols(self.ss.type),df,None,"mktcap",False)

    def volsurface(self, tick, callput = 'call', attr = 'implied'):
        ''' attr is implied,price,openint or any greek'''
        tick = self.currsym if tick is None else tick.upper()
        df = self.ss.df[self.ss.df.ticker.isin([tick.upper()])]
        print(df["lastpx ema20 ema60 ema200".split()])
        print(df["optvol optivol histvol triskvol".split()])
        last = df.lastpx.max()
        df = options_history(tick)
        df = option_surface(df, callput, attr).query("strike % 10 == 0 and abs(strike - @last) < 100")
        return df


    def volhist(self, tick):
        ''' attr is implied,price,openint or any greek'''
        tick = self.currsym if tick is None else tick.upper()
        df = self.ss.df[self.ss.df.ticker.isin([tick.upper()])]
        last = df.lastpx.max()
        df = options_history(tick)
        df['exprmm'] = df.expiration//100
        df = df.query("abs(strike - @last) < 5 and strike % 10 == 0").groupby("exprmm type".split()).last()
        return df.reset_index()["expiration strike mark implied_volatility delta gamma theta".split()].head(5)
        #print(callput.upper(), attr.upper(), self.ss.df.query("ticker == @tick").lastpx.iloc[0])
        #return option_surface(df, callput, attr)

    def financials(self, tick):
        return finstmt(tick)

    def stk(self, tick=None, sort=False):
        tick = self.currsym if tick is None else tick.upper()
        if sort:
            return self.ss.df.query("ticker == @tick").transpose().sort_index()
        return self.ss.df.query("ticker == @tick").transpose()

    def mktcompare(self, tick=None):
        tick = self.currsym if tick is None else tick.upper()
        #print(self.query("ticker == '"+tick+"'"))
        sym = tick + ",SPY,QQQ,IWM," + sect_etfs[self.ss.df.query("ticker == @tick").sect.iloc[0]]
        df = self.ss.df[self.ss.df.ticker.isin(sym.split(","))]
        #'ema60','ema200', 'aroonup', 'aroondwn', 'hangtime', 'rsi', 'bumpiness','recovery', 'precovpx', 'decovpx'
        col = ['ticker', 'pe', 'lastpx', 'optvol', 'volscore', 'daypnl',
            'weekpnl', 'weekdip','mompnl','momdip','qoqpnl',
            'yoypnl', 'corr', 'stlpm', 'dtpm', 'prscore', 'rangescore',
            'qpeak', 'qdrop', 'qrebound', 'lastdevz',
            'beta', 'alpha']
        print("  ")
        print(df.sort_values('mktcap')[['ticker','summ']])
        return query(col, df, None, "mktcap", True)

    def strikes(self, tick, strikes, dte = 30):
        '''Strikes for delta scale (-50 to +50) for given underlying '''
        tick = self.currsym if tick is None else tick.upper()
        df = self.ss.df[self.ss.df.ticker.isin([tick.upper()])]
        sdf = qryspot(tick)
        last = sdf.spotprice.max()
        vol  = sdf.spotvol.max()/100.0
        df = pd.DataFrame()
        for strk in str(strikes).split(","):
            fd = fdelta(float(strk), last, vol, dte)
            otmtype = 'call' if fd > 0 else 'put'
            px = single_option_pricer(otmtype, last, vol, dte, float(strk), False)
            df = append_row(df, {"strike": float(strk), "otmpx": px, "spotvol": vol,
                "spotpx": last, "fdelta": fd })
        return df

    def fdeltas(self, tick, dt = 30, deltas = "-45,-40,-35,-30, 0, 30,35,40,45"):
        '''Strikes for delta scale (-50 to +50) for given underlying '''
        tick = self.currsym if tick is None else tick.upper()
        df = self.ss.df[self.ss.df.ticker.isin([tick.upper()])]
        sdf = qryspot(tick)
        last = sdf.spotprice.max()
        vol = sdf.spotvol.max()/100.0
        df = pd.DataFrame()
        for delta in str(deltas).split(","):
            fd = float(delta)
            strk = round(strike(fd, ovar(last,vol, dt)),2)
            otmtype = 'call' if fd > 0 else 'put'
            px = single_option_pricer(otmtype, last, vol, dt, strk, False)
            df = append_row(df,{"strike": strk, "otmpx": px, "spotvol": vol,
                "spotpx": last, "fdelta": fd })
        return df

    def surface(self, tick, putcall = 'call', mtypeval = 'implied'):
        df = options_history(tick)
        cdf = option_surface(df, putcall, mtypeval)
        return cdf.query("strike % 10 == 0")

    def dcalls(self, tick=None, last=0, vol=0, incr=0):
        tick = self.currsym if tick is None else tick.upper()
        df = self.ss.df[self.ss.df.ticker.isin([tick.upper()])]
        if last == 0: last = float(self.ss.spotprice(tick)['c'])
        if vol == 0:   vol = float(self.ss.spotvol(tick)['volatility'])/100.0
        return option_delta('call', last, vol, incr)

    def dputs(self, tick=None, last=0, vol=0, incr=0):
        tick = self.currsym if tick is None else tick.upper()
        df = self.ss.df[self.ss.df.ticker.isin([tick.upper()])]
        if last == 0: last = float(self.ss.spotprice(tick)['c'])
        if vol == 0:   vol = float(self.ss.spotvol(tick)['volatility'])/100.0
        return option_delta('put',last,vol,incr)

    def calls(self, tick=None, last=0, vol=0, incr=0):
        tick = self.currsym if tick is None else tick.upper()
        df = self.ss.df[self.ss.df.ticker.isin([tick.upper()])]
        if last == 0: last = float(self.ss.spotprice(tick)['c'])
        if vol == 0:   vol = float(self.ss.spotvol(tick)['volatility'])/100.0
        return option_pricer('call', last, vol, incr)

    def puts(self, tick=None, last=0, vol=0, incr=0):
        tick = self.currsym if tick is None else tick.upper()
        df = self.ss.df[self.ss.df.ticker.isin([tick.upper()])]
        #if last == 0: last = df.lastpx.iloc[0]
        if last == 0: last = float(self.ss.spotprice(tick)['c'])
        if vol == 0:   vol = float(self.ss.spotvol(tick)['volatility'])/100.0
        return option_pricer('put',last,vol,incr)

    def tailriskdata(self, stk, vol = 0, fdelta=35):
        ''' Tailrisk '''
        o = self.ss('opricer')(stk)
        if (vol > 0): o.set(0,vol)
        return optcheck(stk, ovar(o.entry.last, o.entry.vol, 60), fdelta, tailrisk)

    def opricer(self, tick, stgy = Stgy.peg_condor, fdel = '', lvd = ovar(0,0,45)):
        '''
        Only strangle or straddle can use negative fdel1 or fdel2
        lvd price and vol are references used for setting the strikes.
        Prices calculated is based on last closing price,vol,dte
        '''
        print("For lvd is ref used for template. For price, it is overridden with curr value")
        opt = mleg(tick, lvd.dt)
        last, vol = opt.curr.last, opt.curr.vol
        if lvd.last > 0 or lvd.vol > 0: opt.set(lvd.last,lvd.vol)
        if len(fdel.strip()) == 0: fdel = Stgy.init(stgy)
        fdels = fdel.split("/")
        # Rounding to 5 will yield most available strikes for most stocks
        round = 5 if len(fdels) == 1 else int(fdels[1])
        fdels = fdels[0].split(",")
        off1 = 0 if len(fdels) < 1 else Stgy.strike(fdels[0],opt.curr,round)
        off2 = 0 if len(fdels) < 2 else Stgy.strike(fdels[1],opt.curr,round)
        off3 = 0 if len(fdels) < 3 else Stgy.strike(fdels[2],opt.curr,round)
        off4 = 0 if len(fdels) < 4 else Stgy.strike(fdels[3],opt.curr,round)
        opt.init(Stgy.template(stgy), off1 , off2, off3, off4)
        opt.set(last,vol)
        return opt

    def mktshifts(self,tick=None):
        ''' Days and mktpx_pctchg prior to direction reversals '''
        print("Days and mktpx_pctchg prior to direction reversals for last 100 days")
        tkr = self.currsym if tick is None else tick.upper()
        df = qrypdb(tkr)
        df = df.sort_values('asof')
        #stdmktpx
        df['adjclose'] = 100.0*df['adjclose']/df.loc[0].adjclose
        df['asof'] = pd.to_datetime(df['asof'].map(str),format='%Y%m%d')

        #retain only px trend reversal days
        df['prev'] = df['adjclose'].shift(1) - df['adjclose']
        df['next'] = df['adjclose'] - df['adjclose'].shift(-1)
        df = df.fillna(0)
        df = df.query("(prev >= 0 and next <= 0) or (prev <= 0 and next >= 0)").copy()
        #generate summary of changes
        df['days'] = df['asof'].shift(-1) - df['asof']
        df['days'] = df['days']/np.timedelta64(1,'D')
        df['pct'] = abs(df['adjclose'] - df['adjclose'].shift(-1))
        df['pctd'] = df['adjclose'].shift(-1) - df['adjclose']
        df = df[:-1]
        print(df[['asof','days','pctd','adjclose']].sort_values("asof",ascending=False).head(20))
        return df[['days','pct']].describe()

    def indleader(self, tick=None, filt = None):
        ''' industry leaders of this ticker '''
        tick = self.currsym if tick is None else tick.upper()
        se = self.ss.df[self.ss.df.ticker == tick.upper()].sect.iloc[0]
        ind = self.ss.df[self.ss.df.ticker == tick.upper()].ind.iloc[0]
        qry = "sect == @se and ind == @ind"
        if filt is not None: qry += " and " + filt
        return self.ss.quote(",".join(self.ss.df.query(qry,engine="python").ticker.values.tolist()))
        #return self.ss.df.query(qry)

    def __call__(self,tkr):
        self.currsym = tkr.upper()
        self.price_action(tkr)

    def pricelevels(self,tick=None):
        tkr = self.currsym if tick is None else tick.upper()
        df1 = self.ss.df.query("ticker == @tkr")
        df = tsmetricsdata(tkr, "MKTPX")
        df['lastpx'] = df[tkr+"/MKTPX"] * df1.lastpx.max()/100
        dict = {
            "yoypx" : df1.lastpx.max() * 100 / (100 + df1.yoypnl.max()) 
            , "qoqpx" : df1.lastpx.max() * 100 / (100 + df1.qoqpnl.max())
            , "mompx" : df1.lastpx.max() * 100 / (100 + df1.mompnl.max()) 
            , "weekpx" : df1.lastpx.max() * 100 / (100 + df1.weekpnl.max()) 
            , "daypx" : df1.lastpx.max() * 100 / (100 + df1.daypnl.max())
            , "lastpx" : df1.lastpx.max()
            , "gap0" : 0
            , "gap1" : 0
            , "p0" : df.tail(90).lastpx.min()
            , "p5" : df.tail(90).lastpx.quantile(0.05)
            , "p16" : df.tail(90).lastpx.quantile(0.16)
            , "p50" : df.tail(90).lastpx.quantile(0.50)
            , "p84" : df.tail(90).lastpx.quantile(0.84)
            , "p95" : df.tail(90).lastpx.quantile(0.95)
            , "p100" : df.tail(90).lastpx.max()
            , "gap2" : 0
            , "gap3" : 0
            , "yoymin" : df.tail(365).lastpx.min()
            , "sma200" : df.tail(200).lastpx.mean()
            , "sma60" : df.tail(60).lastpx.mean()
            , "sma20" : df.tail(21).lastpx.mean()
            , "sma5" : df.tail(5).lastpx.mean()
            , "yoymax" : df.tail(365).lastpx.max()
        }
        keys = []
        for key in dict.keys():
            dict[key] = round(dict[key],2)
            keys.append(key)
        dict['ticker'] = tkr
        adf = pd.DataFrame([dict])
        return graph.bar_group(adf,'ticker',",".join(keys), "Key Price Levels for stock " + tkr, rangescale=True)

    def price_action(self,tick=None):
        tkr = self.currsym if tick is None else tick.upper()
        df1 = loadts(tkr)
        last = self.ss.df.query("ticker == @tkr").lastpx.max()
        df = tsmetricsdata(tkr, "MKTPX,OPTVOL")
        df['vol'] = df[tkr+"/OPTVOL"]/100.0
        vol = df.tail(100).vol.mean()
        df['lastpx'] = df[tkr+"/MKTPX"] * last/100
        df['prevlastpx'] = df.lastpx.shift(1)
        df['mvavg'] = df.lastpx.rolling(21).mean()
        df['mvstd'] = df.lastpx.rolling(21).std()
        df['bbhigh'] = df['mvavg'] + 2 * df['mvstd']
        df['bblow'] = df['mvavg']  - 2 * df['mvstd']
        df = pd.merge(df,df1,on='asof',how='inner')
        advm = df.tail(100).adv.mean()
        df['adv%'] = df.adv/advm
        df['low']  = np.minimum(df.low,df.prevlastpx)
        df['high'] = np.maximum(df.high,df.prevlastpx)
        df['bbcurr'] = df.lastpx - df.bblow
        df['bbwidth'] = df.bbhigh - df.bblow
        bbm  = df.tail(100).bbwidth.mean()
        bbsd = df.tail(100).bbwidth.std()
        df['bbwidthz'] = (df.bbwidth - bbm) / bbsd
        df['px2bb%']  = 100*df.bbcurr/df.bbwidth
        df['l2c']     = df.lastpx - df.low
        df['dr%']     = 100*(df.lastpx - df.prevlastpx)/df.prevlastpx
        df['hlwidth'] = df.high - df.low
        hlm = df.tail(100).hlwidth.mean()
        hlsd = df.tail(100).hlwidth.std()
        df['hlwidthz'] = (df.hlwidth - hlm) / hlsd
        df['px2hl%'] = 100*df.l2c/df.hlwidth

        dic = spotvol(tkr,self.ss.df.query("ticker == @tkr"))

        print("\n************ Basics ************")
        print("lastpx      :", last)
        print("optvol      :",self.ss.df.query("ticker == @tkr").optvol.max())
        print("avgvol      :","{d:.2f}".format(d=100*vol))
        print("volscore    :",self.ss.df.query("ticker == @tkr").volscore.max())
        print("rangescore  :",self.ss.df.query("ticker == @tkr").rangescore.max())
        print("prscore     :",self.ss.df.query("ticker == @tkr").prscore.max())
        print("Sentiment   :", self.ss.df.query("ticker == @tkr").cross.max())
        print("mindrs      :",self.ss.df.query("ticker == @tkr").mindrs.max())
        print("maxdrs      :",self.ss.df.query("ticker == @tkr").maxdrs.max())
        print("")
        print(self.ss.df.query("ticker == @tkr").summ.max())

        print("\n************ Options Data/Implied ************")
        for k,v in dic.items():
            print(k+" = ",str(v))

        print("\n*** 2 Week Price moves ****")
        print(df["asof lastpx dr% vol adv% px2bb% bbwidthz px2hl% hlwidthz".split()].tail(15))

        print("\n************ Averages Price,Vol ************")
        for ts in [5,21,50,200]:
            p,v = df.tail(ts).lastpx.mean(), df.tail(ts).vol.mean()
            print("avg"+str(ts), "\t{d:.2f}\t{e:.2f}".format(d=p,e=v))
        # print("avg5   :", "{d:.2f}\t{e:.2f}".format(d=df.tail(5).lastpx.mean()))
        # print("avg21  :", "{d:.2f}\t{e:.2f}".format(d=df.tail(21).lastpx.mean()))
        # print("avg50  :", "{d:.2f}\t{e:.2f}".format(d=df.tail(50).lastpx.mean()))
        # print("avg200 :", "{d:.2f}\t{e:.2f}".format(d=df.tail(200).lastpx.mean()))

        print("\n********* Percentile vs 6-sigma: (Span=90 days,SPOT=" + str(last)+") *********")
        pct = [0,2.5,16,50,84,97.5,100]
        for i in range(7):
            sd = df.tail(90).lastpx.mean() + (i-3) * df.tail(90).lastpx.std()
            print(str(pct[i]) + "%\t:" + f'{df.tail(90).lastpx.quantile(pct[i]/100.0):.2f}'+"\t"+f'{sd:.2f}')

        print('Monthly Price Range\n',self.ss('yymetric',tick,'MKTPX').agg('min median max'.split()))
        print('Monthly Vol Range\n',self.ss('yymetric',tick,'OPTVOL').agg('min median max'.split()))

        finaldict = { 'lastpx': last }
        finaldict['prscore'] = self.ss.df.query("ticker == @tkr").prscore.max()
        finaldict['rangescore'] = self.ss.df.query("ticker == @tkr").rangescore.max()
        finaldict['momtr'] = self.ss.df.query("ticker == @tkr").momtr.max()
        finaldict['qdrop'] = self.ss.df.query("ticker == @tkr").qdrop.max()
        finaldict['ema20'] = self.ss.df.query("ticker == @tkr").ema20.max()
        finaldict['ema60'] = self.ss.df.query("ticker == @tkr").ema60.max()
        finaldict['ema200'] = self.ss.df.query("ticker == @tkr").ema200.max()
        finaldict['px2bbpct'] = round(df.tail(1)['px2bb%'].max(),2)
        finaldict['momdip'] = self.ss.df.query("ticker == @tkr").momdip.max()
        finaldict['sentiment'] = self.ss.df.query("ticker == @tkr").cross.max()
        finaldict['price_action'] = self.ss.df.query("ticker == @tkr").summ.max()
        finaldict['pnldir'] = self.ss.df.query("ticker == @tkr").pnldir.max()
        finaldict['impliedvol'] = dic['volatility']
        finaldict['histvol'] = self.ss.df.query("ticker == @tkr").histvol.max()
        finaldict['triskvol'] = self.ss.df.query("ticker == @tkr").triskvol.max()
        finaldict['optvol'] = self.ss.df.query("ticker == @tkr").optvol.max()
        finaldict['optivol'] = self.ss.df.query("ticker == @tkr").optivol.max()
        finaldict['volratio'] = self.ss.df.query("ticker == @tkr").volratio.max()
        finaldict['advpct'] = round(df.tail(1)['adv%'].max(),2)
        finaldict['earnings'] = dic['next_earnings_date']
        print('\n ************* Summary ***********')
        for k,v in finaldict.items():
            print(k,'',str(v))

class stock_screener:

    def __init__(self,ctx, show=True):
        self.ctx = ctx
        self.df = ctx.df
        self.type = None
        # If True Displays graphs immendiately
        # else returns graph object
        self.autoshow = show
        self.zscr = screens(self)
        self.zweb = webs(self)
        self.zviz = stkgraph(self)
        self.zmkt = mktsummary(self)
        self.zsym = singlestock(self)

    def columns(self, type = None):
        self.type = None
        if type is not None:
            if type in featuretype:
                self.type = type
        print("type=facts|mktperf|mktdir|mktvol|mktcorr|mktpx")
        return self

    def __getitem__(self, skey):
        ''' Input aapl.optivol '''
        stk, col = skey.lower().split(".")
        qry = "ticker == '" + stk.upper() + "'"
        print("type=facts|mktperf|mktdir|mktvol|mktcorr|mktpx")
        #facts,mktperf,mktdir,mktvol,mktcorr,mktpx
        df = pd.DataFrame()
        if col in featuretype:
            for fld in featuretype[col].split(","):
                #if col == 'mktdir' and fld == 'optvol':
                    #d = self.spotvol(stk)
                    #df = append_row(df, {"name":"earnings_dt","value":d['next_earnings_date'],"desc":"next earnings date"})
                    #df = append_row(df, {"name":"impliedvol","value":d['volatility'],"desc":"30 day implied vol"})
                df = append_row(df,{"name": fld, "value": self.df.query(qry)[fld].max(), "desc": featuredict[fld] })
        for fld in self.df.columns:
            if col in fld:
                df = append_row(df,{"name": fld, "value": self.df.query(qry)[fld].max(), "desc": featuredict[fld] })
        return df["name value desc".split()]

    def __call__(self, func=None, *args):
        arr = ['self','self.zmkt','self.zscr','self.zsym','self.zweb','self.zviz']
        # CHANGED: eval(c) replaced by an explicit name->object map (same lookup order)
        objs = {'self': self, 'self.zmkt': self.zmkt, 'self.zscr': self.zscr,
                'self.zsym': self.zsym, 'self.zweb': self.zweb, 'self.zviz': self.zviz}
        if func is not None:
            for c in arr:
                if func in dir(objs[c]):
                    f = getattr(objs[c],func)
                    sig = signature(f)
                    # execute automatically if args match
                    if len(sig.parameters) == len(args) or len(args) > 0:
                        o = f(*args)
                        if hasattr(o,'show') and self.autoshow:
                            o.show()
                            return
                        else: return o
                    print(inspect.getfullargspec(f)[0])
                    return f
            self.zsym.__call__(func.upper())
            return lambda *x: None
        # no-args => list all available functions
        for c in arr:
            code(objs[c], c)   # CHANGED: eval(c) -> objs[c]
        return lambda *x: None

    def mode(self, mode = Mode.all):
        if mode == 'ALL': self.df = self.ctx.df
        elif mode == 'LARGE': self.df = self.ctx.df.query("mktcap > 20")
        elif mode == 'SMALL': self.df = self.ctx.df.query("mktcap < 20")
        elif mode == 'WATCH':
            #self.df = self.ctx.df[self.ctx.df.ticker.isin(tickers().split(","))]
            arr = tickers().split(",")
            self.df = self.ctx.df.query("ticker in @arr")
        elif mode == 'OPTION':
            #self.df = self.ctx.df[self.ctx.df.ticker.isin(tickers().split(","))]
            arr = optstocks().split(",")
            self.df = self.ctx.df.query("ticker in @arr")
        elif mode == 'NOWATCH':
            arr = tickers().split(",")
            self.df = self.ctx.df.query("ticker not in @arr")
        else:
            self.df = self.ctx.df.query(mode)

    def spotprice(self, tick):
        tick = tick.upper()
        df = self.df.query("ticker == @tick")
        dict = spotprice(tick)
        if dict['c'] == 0: dict['c'] = df.lastpx
        return dict

    def spot(self, ticks):
        df = pd.DataFrame()
        for stk in ticks.split(","):
            df1 = qryspot(stk)
            df1['ticker'] = stk
            df = pd.concat([df, df1], ignore_index=True)
        return df["ticker spotprice spotvol".split()]

    def spotvol(self, tick):
        tick = tick.upper()
        df = self.df.query("ticker == @tick")
        return spotvol(tick,df)

    def movers(self, pnl, time = 'daypnl', mktcap=0.01, sect=None, ind=None):
        ''' The sign of the pnl implicitly determines gainer or loser '''
        print("Gainers/Losers in a " + time + " within all mkt, sector, ind or cap")
        fil = " > " if pnl > 0 else " < "
        fil = time + fil + str(pnl)
        #if size is None:     fil += " and mktcap > 20 "
        if sect is not None: fil += " and sect == '" + sect + "'"
        if ind is not None:  fil += " and ind == '" + ind + "'"
        #if size is not None: fil += " and mktsize == '" + size + "'"
        fil += " and mktcap > " + str(mktcap)
        return self.squery(fil, time, '', pnl < 0)

    def watch(self,stks=tickers(), sort='weekpnl'):
        print("\t\t\t\t\t\t\t\t\t\t\t STOCKS BY " + sort + " (Best -> Worst) ")
        stkslice = stks.split(',') if isinstance(stks, str) else stks
        qry = "ticker in @stkslice"
        #col = "ticker,lastpx,daypnl,range,rangescore,prscore,pe,summ,weekpnl,weekdip,yoypnl,optvol,volscore,xadv,volratio,corr"
        col = "name ticker mktcap lastpx pe corr prscore px2bbpct momtr daypnl weekpnl mompnl optvol optivol histvol volratio xadv"
        #cols(self.type,sort)
        #col += "," + sort
        return query(col.split(" "),self.df.query(qry), None, sort, True)

    def quote(self, y = None, sort='mktcap', asc=True):
        col = "ticker,lastpx,daypnl,range,rangescore,prscore,pe,name,weekpnl,weekdip,yoypnl,histvol,optvol,volscore,xadv,volratio"
        qry = "mktcap > 20" if y is None else "ticker in @y.upper().split(',')" if len(y.split(",")) > 1 else y
        return query(col.split(","), self.df.query(qry), None, 'weekdip', asc)

    def query(self, y = None, x = None, sort='mktcap',asc=False):
        col = cols(self.type) if x is None else cols(self.type,x)
        qry = "mktcap > 20" if y is None else y
        return query(col,self.df,qry,sort,asc)

    def squery(self, y = "mktcap > 20", sort='mktcap', x = "", asc=False):
        cols = "name ticker mktcap lastpx impvol optvol triskvol histvol rangescore px2bbpct momtr daypnl weekpnl mompnl qoqpnl yoypnl volratio xadv".split()
        xys = y + " " + x + " " + sort
        for c in ",()-*+%/":
            xys = xys.replace(c,' ')
        [cols.append(f) for f in xys.split() if f in self.df and f not in cols]
        return query(cols,self.df,y,sort,asc)

    def slice(self, rows, cols = None, sort='mktcap', asc=False):
        qcols = "name ticker".split()
        if cols is not None and len(cols.strip()) == 0: cols = None
        if cols is not None: qcols += cols.split(",")
        if len(rows.split()) == 1 and len(rows.split(",")) >= 1:
            aqry = "ticker in ['"
            for tick in rows.upper().split(","):
                aqry += tick + "','"
            aqry += "']"
        else: 
            aqry = rows
        xys = rows + " " + sort
        for c in ",()-*+%/":
            xys = xys.replace(c,' ')
        [qcols.append(f) for f in xys.split() if f in self.df and f not in qcols]
        return query(qcols,self.df,aqry,sort,asc)

    # Momentum vs Mean reversion Strength (mmrs) - Fwd daypnl(Y) vs weekpnl(X)
    #         qtr is a good time period for tracking
    #         Each X creates distribution of Y values to summarize statistically
    # day stop loss pct (dslp) = 5th quantile of lowpx/lastpx ratio (in last 30 days)

class Group:
    def __init__(self, ss, qry):
        df = ss.slice(qry)
        self.df = ss.df[ss.df.index.isin(df.index)].select_dtypes(include=['float64'])

    # returns percentile of this in the target group
    def pct(self, tgtgrp, col):
        val = self.df.median()[col]
        if len(self.df) < 10:
            val = self.df[col].mean()[col]
        return (tgtgrp.df[col] < val).mean() * 100 

    # semi.level(mkt) => where semi is at in a compared group called mkt
    def level(self, mkt):
        return self.pct(mkt, 'rangescore')

    def direction(self, mkt):
        return self.pct(mkt, 'px2bbpct')
    
    def correlation(self, mkt):
        return self.pct(mkt, 'corr')
    
    def performance(self, mkt):
        return self.pct(mkt, 'momdip')


