"""Drop-in replacement for legacy ``mystk/python/svcreader.py``.

Same function names, signatures and returned frame shapes as legacy, but every call goes
through the new socket protocol (``mystock_ui.api``) instead of the old CSV line protocol.
The legacy ``globalcontext`` class and the derived-column functions (``qdropratio``,
``dpnl``, ``cross``, ``tier``) below the boundary section are copied verbatim; changed
lines are marked ``# CHANGED:``.
"""
import sys
import time
import threading
import pandas as pd
import numpy as np
import inspect
from pandas.io.clipboard import copy
import os
import socket
import json
from io import StringIO
# CHANGED: 'import sqlalchemy' removed (unused; the frontend never opens a database)

from .. import api, config
from ..client import ApiError

#def tsmerge(tick, metric, span):
import numpy as np
from numpy import fft
from sys import platform

def is_production():
    return True if platform == "linux" or platform == "linux2" else False

# CHANGED: host/port of the backend come from MYSTOCK_API_HOST/MYSTOCK_API_PORT.
STOCKFEED_HOST = config.API_HOST
STOCKFEED_PORT = config.API_PORT


########################## data boundary (new code) ##########################
class _TTLCache:
    """Short-lived cache for time-series reads (charts/pricers re-read the same series)."""

    def __init__(self, ttl, maxsize=256):
        self.ttl, self.maxsize = ttl, maxsize
        self._data = {}
        self._lock = threading.Lock()

    def get(self, key, loader):
        now = time.monotonic()
        with self._lock:
            hit = self._data.get(key)
            if hit is not None and now - hit[0] < self.ttl:
                return hit[1].copy()
        value = loader()
        with self._lock:
            if len(self._data) >= self.maxsize:
                self._data.pop(min(self._data, key=lambda k: self._data[k][0]), None)
            self._data[key] = (now, value)
        return value.copy()

    def clear(self):
        with self._lock:
            self._data.clear()


_cache = _TTLCache(config.SERIES_TTL_SECONDS)


def clear_cache():
    _cache.clear()


def _ymd(series):
    return pd.to_datetime(series).dt.strftime("%Y%m%d").astype(int)


def _since(timespan):
    return None if timespan in (0, "0", None, "") else api.iso_date(timespan)


def tsmetricsdata(ticks, metrics, timespan = 0):
    """Wide ``asof, TICK/METRIC...`` frame from ONE ``metrics.batch`` call (legacy: N*M calls).

    Column labels keep the caller's spelling (callers index ``df[stk+"/MKTPX"]``).
    """
    tlist = [t for t in str(ticks).split(",") if t != ""]
    mlist = [m for m in str(metrics).split(",") if m != ""]
    since = _since(timespan)
    key = ("metrics", tuple(t.upper() for t in tlist), tuple(m.upper() for m in mlist), since)
    long = _cache.get(key, lambda: api.metrics_batch(tlist, mlist, since))
    cols = [t + "/" + m for t in tlist for m in mlist]
    if len(long) == 0:
        return pd.DataFrame({"asof": pd.Series(dtype="datetime64[ns]"), **{c: pd.Series(dtype=float) for c in cols}})
    tmap = {t.upper(): t for t in tlist}
    mmap = {m.upper(): m for m in mlist}
    long["col"] = long["ticker"].str.upper().map(tmap) + "/" + long["metric"].str.upper().map(mmap)
    df = long.pivot_table(index="asof", columns="col", values="value", aggfunc="last").sort_index()
    df = df.reindex(columns=cols)
    df.columns.name = None
    df = df.reset_index()
    df["asof"] = pd.to_datetime(df["asof"])
    return df

def readmetrics(tick, metric, timespan = 0):
    df = tsmetricsdata(tick, metric, timespan)
    df = df.rename(columns={tick + "/" + metric: "val"})
    return df[['asof',"val"]]

def readpricepeg(tick, type, pegdt):
    df = readprice(tick, type)
    df = df[df["asof"] >= pd.Timestamp(api.iso_date(pegdt))].reset_index(drop=True)
    if len(df) == 0: return df
    px = df.loc[0,'val']
    df['val'] = (100.0*df['val'])/px
    return df[['asof',"val"]]

def readohlc(tick, start = 20150101, end = 20990101):
    """One ``prices.ohlc`` call (legacy: five ``OHLC,<fld>`` calls), adjusted prices."""
    key = ("ohlc", tick.upper(), api.iso_date(start), api.iso_date(end))
    resdf = _cache.get(key, lambda: api.prices_ohlc(tick, start, end))
    resdf = resdf.sort_values("asof").reset_index(drop=True)
    resdf['asof'] = pd.to_datetime(resdf['asof'])
    return resdf["asof open low high close prev".split()]

def readprice(tick, type):
    df = _cache.get(("series", tick.upper(), type.upper()), lambda: api.prices_series(tick, type))
    df = df.sort_values("asof").reset_index(drop=True)
    df['asof'] = pd.to_datetime(df['asof'])
    df = df.rename(columns={"value": "val"})
    return df[['asof',"val"]]

def request_csv(name,cnt=1):
    """Legacy ``CSV,<name>,<cnt>`` (static CSV files) -> the matching new op."""
    if name == "out":
        df = api.screener_stocks()
    elif name == "port":
        df = api.screener_portfolios()
    elif name == "queries":
        df = api.config_queries()
    elif name == "dynamic_reports":
        df = api.config_reports()
    elif name == "prediction":
        df = api.predictions_list()
        if len(df): df["asof"] = _ymd(df["asof"])   # legacy prediction.csv held yyyymmdd ints
        # LEGACY BUG FIXED: legacy called request_csv("prediction") with cnt=1, which read
        # only the CSV header line, so the prediction table was always empty.
        return df
    else:
        raise ApiError("BAD_REQUEST", "unknown legacy csv " + str(name))
    # legacy returned at most cnt-1 data rows (cnt counted the header line)
    return df.head(cnt - 1).reset_index(drop=True) if cnt > 1 else df

def login(user,passwd):
    """Returns the session token ('' when the credentials are rejected)."""
    try:
        return api.auth_login(user, passwd)["token"]
    except ApiError as e:
        if e.code == "UNAUTHORIZED": return ""
        raise

def logout(session):
    api.auth_logout(session)

def booktrade(session, pos, port, buysell, legsym, px, qty, upx = None, uvol = None, tradedt = None):
    res = api.trades_book(session, port, pos, buysell, legsym, px, qty, upx, uvol, tradedt)
    return res.get("trade_id")

def loadts(tkr):
    df = qrypdb(tkr)
    df['asofint'] = df['asof']
    df['asof'] = pd.to_datetime(df['asof'].map(str),format='%Y%m%d')
    for col in "adjclose,open,close,high,low".split(","):
        df[col] = df[col]/100
    return df

def qryshadow(sym, initdt, ivol, tiskvol):
    """``backtest.shadow``. Legacy passed the two vol strings through; empty -> server estimates."""
    def num(v):
        v = str(v).strip() if v is not None else ""
        return float(v) if v != "" else None
    optvol, riskvol = num(ivol), num(tiskvol)
    if optvol is None or riskvol is None: optvol = riskvol = None
    return api.backtest_shadow(sym, initdt, optvol, riskvol)

def qrypdb(sym):
    """Legacy ``QRY,PRICE`` shape: asof yyyymmdd int, OHLC/adjclose in CENTS, volume, ticker.

    ``prices.history`` returns dollars; they are scaled to cents here only so that the copied
    legacy callers (``loadts`` and the ``/100.0`` in ``stkgraph.scatter``) keep working
    unchanged -- the net result they see is dollars. (Legacy cents were truncated ints;
    no truncation is done here.)
    """
    df = _cache.get(("history", sym.upper()), lambda: api.prices_history(sym))
    df = df.sort_values("asof").reset_index(drop=True)
    df["asof"] = _ymd(df["asof"]) if len(df) else pd.Series(dtype=int)
    for col in "adjclose,open,close,high,low".split(","):
        df[col] = df[col] * 100.0
    df["ticker"] = sym.upper()
    return df

def qrytrades(user):
    """``trades.list`` (arg is the session token) in legacy shape: trades_id, int tradedt."""
    df = api.trades_list(user)
    df = df.rename(columns={"trade_id": "trades_id"})
    if len(df): df["tradedt"] = _ymd(df["tradedt"])
    return df

def qryspot(sym):
    """``quote.live`` as the legacy ``QRY,LIVE`` frame: spotprice, spotvol (IV %), earn (yyyymmdd or 0)."""
    q = api.quote_live(sym)
    earn = q.get("next_earnings")
    earn = int(str(earn).replace("-", "")[:8]) if earn else 0
    return pd.DataFrame([{"spotprice": float(q.get("spot_price") or 0.0),
                          "spotvol": float(q.get("spot_vol") or 0.0), "earn": earn}])

def cboevix_frame():
    """``market.vix`` shaped like the CBOE CSVs merged by legacy ``cboevix``: DATE (MM/DD/YYYY), VIX, SKEW."""
    df = api.market_vix()
    df = df.sort_values("date").reset_index(drop=True)
    return pd.DataFrame({"DATE": pd.to_datetime(df["date"]).dt.strftime("%m/%d/%Y"),
                         "VIX": df["vix"].astype(float), "SKEW": df["skew"].astype(float)})
def request_df(req):
    # CHANGED: the legacy CSV line protocol (port 8050) is gone; use mystock_ui.api.
    raise NotImplementedError("request_df: legacy line protocol removed - use mystock_ui.api / the functions above")

def send_request(msg):
    # CHANGED: the legacy CSV line protocol (port 8050) is gone; use mystock_ui.api.
    raise NotImplementedError("send_request: legacy line protocol removed - use mystock_ui.api / the functions above")
######################## end of data boundary ##########################

class globalcontext:

    def cboevix(self):
        # CHANGED: VIX/SKEW come from the backend op market.vix (the backend fetches CBOE);
        # the frontend no longer downloads the CBOE CSVs itself. Same DATE/VIX/SKEW shape.
        try:
            vf = cboevix_frame()
        except ApiError as e:
            if e.code != "UPSTREAM": raise
            vf = pd.DataFrame()
        if len(vf) == 0:
            # CHANGED: VIX feed down -> keep the app usable; one NaN row dated db_date
            # so lastdt (below) still resolves.
            dbd = api.iso_date(api.ping().get("db_date")) or time.strftime("%Y-%m-%d")
            vf = pd.DataFrame({"DATE": [dbd[5:7] + "/" + dbd[8:10] + "/" + dbd[0:4]],
                               "VIX": [np.nan], "SKEW": [np.nan]})
        return vf

    def __init__(self):
        self.vix = self.cboevix()
        dt = self.vix.tail(1).DATE.max().split('/')
        self.lastdt = int(dt[2] + dt[0] + dt[1])
        #self.m2df = pd.read_csv("/Users/selvamdoraisamy/local/Desktop/personal/eventifs/java/m2.csv")
        #self.m2df['asof'] = self.m2df['asof'].astype('datetime64[ns]')
        print("Connecting",STOCKFEED_HOST,STOCKFEED_PORT)  # CHANGED: values now from env
        self.df = request_csv("out",10000)
        self.df['summ'] = self.df.summ.str.replace('u','↑').str.replace('d','↓')
        self.df['pred'] = 0
        self.df['mktcap'] = self.df['mktcap']/1_000_000_000
        self.df['sect'] = self.df.sect.str.upper()
        self.df['ind'] = self.df.ind.str.upper()
        #self.df['range'] = self.df.range * self.df.rangescore / 100.0
        self.df['pe'] = self.df.apply(lambda row: 0 if row.eps <= 0 else row.lastpx/row.eps,axis=1)
        self.df = tier(self.df, 'pe', [0,10,20,50],['NONE','LOW','FAIR','HIGH','XTREME'], 'petype')
        self.df = tier(self.df, 'mktcap', [5,10,20,50,100,250],['5B','10B','20B','50B','100B','250B','250B+'], 'mktsize')
        ''' expectedmove (=0.5*range) * sqrt(365/days) '''
        #self.df['longvol1'] = 0.5 * self.df.range * pow(365.0/abs(datediff(20220601)), 0.5)

        #self.df = tier(self.df, 'optvol', [5,10,15,20,25,35,50,70,90],"1 2 3 4 5 6 7 8 9 10".split(), 'volscale')
        self.port = request_csv("port",1000)

        mkt = "BITO,TLT,IWM,QQQ,SPY,RSP,DIA,RSPT,SMH,UVXY"
        #other = "SLYG,TOPT,CALF,XAR,ARKK,KBE,SCHG,XSD,VBR,SCHA,SCHD,SCHV,BUG,CIBR,ITA,TAN,ICLN,KRE"
        sect = "XLU,XLE,XME,XLY,XRT,XLC,XLF,VNQ,XLK,XLRE,XLB,XLI,XLP,XLV,XHB,XBI"
        comm = "CPER,USO,BNO,XOP,SOYB,UNG,SLV,GLD,WEAT,GDX,GDXJ,URA,CORN"
        country = "EWZ,EWJ,EWU,FXE,FEZ,EWG,DXJ,KWEB,FXF,NFTY,VGK,EPI,EWC,INDA,EWY,INDY,FXI,HEWJ,EEM,EWA,EWS,EWT,EWW,EWH,SMIN"
        self.df.loc[self.df.ticker.isin(mkt.split(",")), 'sect']     = 'MKT'
        self.df.loc[self.df.ticker.isin(sect.split(",")), 'sect']    = 'SECT'
        self.df.loc[self.df.ticker.isin(comm.split(",")), 'sect']    = 'COMM'
        self.df.loc[self.df.ticker.isin(country.split(",")), 'sect'] = 'WORLD'
        #self.df.loc[self.df.ticker.isin(other.split(",")), 'sect']   = 'CUSTOM'

        self.df["recovery"] = self.df.qrebound*100/(self.df.qrebound+self.df.qdrop)
        self.port["recovery"] = self.port.qrebound*100/(self.port.qrebound+self.port.qdrop)

        self.df = qdropratio(self.df)

        for func in [dpnl,cross]:
            self.port = func(self.port)
            self.df   = func(self.df)

        # Will make 2 week prediction and test how good it is
        self.pred = request_csv("prediction")
        # CHANGED: loadts is served from a TTL cache, so each distinct ticker costs one
        # prices.history call per context rebuild (the context itself is cached, see data.py).
        for i, row in self.pred.iterrows():
            try:
                df = loadts(row.ticker)
            except ApiError:   # CHANGED: unknown/delisted ticker must not break the app
                continue
            if len(df) == 0: continue   # CHANGED: guard empty history
            for f in "open high low close".split():
                self.pred.at[i,f] = df.at[len(df)-1,'adjclose']
            df = df[df.asofint >= row['asof']].head(15)
            if len(df) > 0:
                self.pred.at[i,'open']  = df.head(1).adjclose.max()
                self.pred.at[i,'high']  = df.adjclose.max()
                self.pred.at[i,'low']   = df.adjclose.min()
                self.pred.at[i,'close'] = df.tail(1).adjclose.max()

    def predict(self, ticker = None, reason=None, mktpx = 0, tgtpx = 0):
        if ticker == None: return self.pred
        dt = int(time.strftime("%Y%m%d"))
        dict = { "ticker": ticker, "asof": dt, "tgtpx": tgtpx, "open": mktpx,
                 "high": mktpx, "low": mktpx, "close": mktpx }
        from .utils import append_row   # CHANGED: legacy NameError (append_row was never imported here)
        api.predictions_add(ticker, tgtpx, mktpx)   # CHANGED: replaces the atexit prediction.csv write
        self.pred = append_row(self.pred,dict)
        return self.pred

    def portfolio(self, wgtype = None):
        #name daypnl weekpnl mompnl qoqpnl yoypnl dpnl optvol prscore rangescore range corr qdrop qrebound recovery weekdip momdip beta alpha wgtype
        df = self.port["name px2bbpct daypnl weekpnl mompnl momtr qoqpnl yoypnl optvol optivol histvol prscore corr beta wgtype".split()]
        if wgtype is None: return df
        wgtype = wgtype.upper()
        return df.query("wgtype == @wgtype")

def qdropratio(df):
    mktvol = df[df.ticker == 'SPY']['histvol'].max()
    mktdrop = df[df.ticker == 'SPY']['qdrop'].max()
    mktdropavg = df[df.ticker == 'SPY']['qdropavg'].max()
    mktadjdrop = (mktdrop-mktdropavg)/mktvol
    def pfunc(qdrop,qdropavg,vol,mktadjdrop):
        # Finds vol adjusted excessive drawdown that is idiosyncratic
        #return 10 * (/vol - mktadjdrop)
        return (qdrop-qdropavg)/(0.08*vol)
    df['qdropratio'] = df.apply(lambda row: pfunc(row['qdrop'],row['qdropavg'],row['histvol'],mktadjdrop),axis=1)
    return df

def dpnl(df):
    ''' worst of annualized yoy, qoq, mom pnls '''
    def pfunc(y,q,m):
        y = (y - q) * (4.0/3.0)
        q = (q - m) * 6.0
        m = m * 12
        #print(y,q,m,(1+0.01*y) * (1+0.01*q) * (1+0.01*m))
        #return pow(abs((1+0.01*y) * (1+0.01*q) * (1+0.01*m)),0.33)
        return y if y < q and y < m else q if q < y and q < m else m
    df['dpnl'] = df.apply(lambda row: pfunc(row['yoypnl'],row['qoqpnl'],row['mompnl']),axis=1)
    return df

'''
MKT-EMA60-EMA200
100*95*90 = Bull
100*90*95 = Bearish, Strong reversal (Bear2Bull+)
100*95*105 = Bearish, Weak reversal (Bear2Bull)
100*105*95 = Bullish, Weak reversal (Bull2Bear)
100*110*105 = Bullish Strong reversal (Bull2Bear+)
100*105*110 = Bear
'''
def cross(df):
    ''' worst of annualized yoy, qoq, mom pnls '''
    def pfunc(e50,e200):
        if e50 < 100:
            if e200 > 100: return "Bear2Bull"
            return "Bullish" if e200 < e50 else "Bear2Bull+"
        else:
            if e200 < 100: return "Bull2Bear"
            return "Bull2Bear+" if e200 < e50 else "Bearish"
    df['cross'] = df.apply(lambda row: pfunc(row['ema20'],row['ema60']),axis=1)
    return df

def tier(df,fld,darr,arr,lbl):
    def tfunc(val,darr,arr):
        res = 0
        for a in darr:
            if val > a:
                res = res+1
        return arr[res]
    df[lbl] = df.apply(lambda row: tfunc(row[fld],darr,arr),axis=1)
    return df
