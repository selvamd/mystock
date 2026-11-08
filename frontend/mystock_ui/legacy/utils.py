import sys
import pandas as pd
import numpy as np
import inspect
from pandas.io.clipboard import copy
# CHANGED: removed 'import sqlalchemy' (sfupload below raises NotImplementedError)
import os
import socket
import json
from io import StringIO
from .svcreader import *
import datetime 

#def tsmerge(tick, metric, span):
import numpy as np
from numpy import fft

# CHANGED: removed requests/bs4 and the hard-coded Alpha Vantage URL+key; data now comes from
# the backend (options.chain, fundamentals.statements, quote.live) via ..api.
from .. import api
alphaurl = ''   # CHANGED: hard-coded Alpha Vantage URL with API key removed (name kept)

def append_row(df, row):
    return pd.concat([df, pd.DataFrame([row])], ignore_index=True)

# https://gist.github.com/tartakynov/83f3cd8f44208a1856ce
def fourierExtrapolation(x, n_predict, n_harm = 10):
    n = x.size
    t = np.arange(0, n)
    p = np.polyfit(t, x, 1)         # find linear trend in x

    x_notrend = x - p[0] * t        # detrended x
    x_freqdom = fft.fft(x_notrend)  # detrended x in frequency domain
    f = fft.fftfreq(n)              # frequencies

    indexes = list(range(n))
    # sort indexes by frequency, lower -> higher
    #indexes.sort(key = lambda i: np.absolute(f[i]))
    indexes.sort(key=lambda i: np.absolute(x_freqdom[i]))
    indexes.reverse()

    t = np.arange(0, n + n_predict)
    restored_sig = np.zeros(t.size)
    for i in indexes[:1 + n_harm * 2]:
        ampli = np.absolute(x_freqdom[i]) / n   # amplitude
        phase = np.angle(x_freqdom[i])          # phase
        #print(1/f[i],ampli,phase,x_freqdom[i])
        restored_sig += ampli * np.cos(2 * np.pi * f[i] * t + phase)
    return restored_sig + p[0] * t

def root2decimal(a, b, zerofunc, eps = 0.01, aval = None, bval = None):
    c = (a+b)/2
    cval = zerofunc(c)
    if aval is None: aval = zerofunc(a)
    if bval is None: bval = zerofunc(b)
    if abs(cval) <= eps:
        return c
    if cval * bval < 0:
        return root2decimal(c, b, zerofunc, eps, cval, bval)
    elif cval * aval < 0:
        return root2decimal(c, a, zerofunc, eps, cval, aval)
    return c

#class to help with ipython autofill
class autofill:
    def __create__(self, nam, val, desc = None):
        setattr(self, nam, val)

def code(py, log=""):
    import inspect
    arr = [f for f in dir(py) if
        type(getattr(py, f, None)).__name__ == 'method' and
        not f.startswith('_')]

    if len(arr) > 0:
        arr.sort()
        print(log, arr)
    else: print(inspect.getsource(py))
    return arr

def sfupload(data_frame, schema, table_name, truncate=False, create=True):
    # CHANGED: body removed (Snowflake via sqlalchemy with hard-coded credentials). The
    # frontend never opens a database and the backend protocol has no upload op.
    raise NotImplementedError("sfupload: no backend op exists for uploading frames to a database")

def spotprice(sym):
    return {"c": qryspot(sym).spotprice.max() ,"d": 0,"dp": 0,"h": 0,"l": 0,"o": 0,"pc": 0,"t": 0}

def qryspotfin(sym):
    # CHANGED: was a direct Finnhub call; now quote.live. Only 'c' (and 'dp'=0) are provided.
    return {"c": qryspot(sym).spotprice.max(), "dp": 0}

def parse_float(string):
    try:
        string = string.replace(',','')
        return float(string)
    except ValueError:
        return 0

def spotpxvol(stk):
    spot = qryspotfin(stk)
    last = float(spot['c'])
    df = options_history(stk)
    df['exprmm'] = df.expiration//100
    df = df.query("abs(strike - @last) < 5 and strike % 10 == 0").groupby("exprmm type".split()).last()
    ivol = df.head(4).implied_volatility.mean()
    return last, round(ivol,2), float(spot['dp'])

def smart_cast_df(df):
    def convert_if_possible(col):
        try:
            return pd.to_numeric(col)
        except ValueError:
            return col
    df = df.apply(convert_if_possible)
    return df

def options_history(sym):
    # CHANGED: was Alpha Vantage HISTORICAL_OPTIONS; now the backend op options.chain, reshaped
    # to the legacy AV frame: type call/put, date/expiration as yyyymmdd ints, numeric columns.
    df = api.options_chain(sym)
    if len(df) == 0: return pd.DataFrame()
    df = df.rename(columns={"contract_id": "contractID"})
    df['type'] = df['type'].astype(str).str.upper().map({'C': 'call', 'P': 'put', 'CALL': 'call', 'PUT': 'put'})
    df['date'] = pd.to_datetime(df['asof']).dt.strftime('%Y%m%d')
    df['expiration'] = pd.to_datetime(df['expiration']).dt.strftime('%Y%m%d')
    df = df.drop(columns=['asof'])
    return smart_cast_df(df)

def options_summary(sym):
    df = options_history(sym)
    df['expr'] = df.expiration//100
    dfciv = df.query("abs(delta - 0.5) < 0.1").groupby('expr')[['implied_volatility']].mean().reset_index()
    dfpiv = df.query("abs(delta + 0.5) < 0.1").groupby('expr')[['implied_volatility']].mean().reset_index()
    dfcoi = df.query("type == 'call'").groupby('expr')[['open_interest']].sum().reset_index()
    dfpoi = df.query("type == 'put'").groupby('expr')[['open_interest']].sum().reset_index()
    dfout = pd.merge(dfciv,dfcoi,on='expr',how='inner')
    dfout = pd.merge(dfout,dfpiv,on='expr',how='inner')
    dfout = pd.merge(dfout,dfpoi,on='expr',how='inner')
    dfout.rename(columns={'implied_volatility_x': 'imp_vol_call', 'open_interest_x': 'openint_call'}, inplace=True)
    dfout.rename(columns={'implied_volatility_y': 'imp_vol_put', 'open_interest_y': 'openint_put'}, inplace=True)
    dfout['cpratio'] = np.round(dfout['openint_call']/dfout['openint_put'],2)
    dfout['cpskew'] = np.round(dfout['imp_vol_call']/dfout['imp_vol_put'],2)
    dfout['imp_vol_call'] = np.round(dfout['imp_vol_call'] * 100,2) 
    dfout['imp_vol_put'] = np.round(dfout['imp_vol_put'] * 100, 2)
    dfout['expr'] = dfout['expr'].astype(str)
    return dfout["expr imp_vol_call imp_vol_put cpskew openint_call openint_put cpratio".split()]
    

def option_surface(df, callput, attr, interpolate=False):
    # attr is implied_volatility,delta,mark,open_interest 
    if attr == 'implied':    attr = 'implied_volatility'
    if attr == 'price':      attr = 'mark'
    if attr == 'openint':    attr = 'open_interest'
    df = df.sort_values("strike expiration".split())
    ivmap = {}
    for idx, row in df.iterrows():
        key = str(row['strike'])
        key1 = str(row['expiration'])
        #if row['expiration'] not in expiries: continue
        if abs(row['delta']) < 0.02 or abs(row['delta']) > 0.98: continue
        if int(row['strike']) != row['strike']: continue
        if callput != row['type']: continue
        if key not in ivmap: ivmap[key] = {}
        #print(key, key1, row['implied_volatility'])
        ivmap[key][key1] = float(row[attr])
    # Sort by strike to get value
    df = pd.DataFrame.from_dict(ivmap,orient='index')
    df['strike'] = df.index.astype(float)
    df.set_index('strike',inplace=True)
    df = smart_cast_df(df)
    df = df.sort_values(['strike'],ascending=True)
    if interpolate:
        df = df.interpolate(method='linear', limit_direction='both', axis=0)
    ##  rearrange columns in increase exercise date
    cols = list(df.columns)
    cols.sort()
    df1 = pd.DataFrame()
    df1['strike'] = df.index
    for col in cols:
        df1[col[0:6]] = df[col].values
    df1.set_index('strike',inplace=True)
    return df1

# def ypnlstmt(sym):
#     url = "https://finance.yahoo.com/quote/"+ sym + "/financials/"
#     cols = "Breakdown,Total Revenue,Cost of Revenue,Gross Profit,Operating Expense,Operating Income,Net Income Common Stockholders,EBITDA,EBIT"
#     return ytblscrape(url, sym, cols.split(","), 5)

# def ybalsheet(sym):
#     url = "https://finance.yahoo.com/quote/"+ sym + "/balance-sheet/"
#     cols = "Breakdown,Total Assets,Total Liabilities Net Minority Interest,Total Equity Gross Minority Interest,Common Stock Equity,Total Debt,Net Debt,Share Issued"
#     return ytblscrape(url, sym, cols.split(','), 4)

# def ytblscrape(url, sym, rowvals, icols):
#     headers={"user-agent": "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"}
#     response = requests.get(url, headers=headers, timeout=5)
#     #print("response", len(response.content), response.status_code)
#     #print("response", response.content)
#     soup = BeautifulSoup(response.content, "html.parser")
#     elements = soup.findAll('div')
#     # Parse it inro a dictionary first
#     dict = {}
#     for row in rowvals:
#         dict[row] = []
#     key = None
#     for element in elements:
#         txt = element.text.strip()
#         if txt in dict: 
#             key = txt
#         elif key in dict: 
#             if len(txt) > 0: dict[key].append(txt)
#             if len(dict[key]) == icols: key = None
#     #print(dict)
#     # turn it into  a dataframe
#     df = pd.DataFrame()    
#     for idx in range(len(dict['Breakdown'])):
#         dfr = {}
#         for key in rowvals:
#             val = '0' if len(dict[key]) <= idx else dict[key][idx]
#             val = parse_float(val) if key != 'Breakdown' else val
#                   #str(datetime.date.today().year - idx)
#             colname = " ".join(key.split()[0:2])
#             if colname == "Cost of": colname = "Direct Cost"
#             dfr[colname] = [val]
#         df = pd.concat([df, pd.DataFrame(dfr)])
#     if len(df) > 0:
#         df = df.set_index("Breakdown")
#     return df

def _finfield(name):
    return str(name).replace('_', '').lower()

def _finreports(sym, statement):
    # CHANGED: rebuilds the Alpha Vantage 'quarterlyReports' frame (newest first, AV field
    # names) from fundamentals.statements (long format: statement, fiscal_date, period, field, value).
    long = api.fundamentals_statements(sym)
    if len(long) == 0: return pd.DataFrame()
    long = long[(long.statement.astype(str).str.lower() == statement) & (long.period.astype(str).str.lower() == 'quarterly')]
    if len(long) == 0: return pd.DataFrame()
    avnames = { _finfield(n): n for n in ['totalRevenue','grossProfit','netIncome','totalAssets',
        'totalLiabilities','totalShareholderEquity','cashAndShortTermInvestments'] }
    long = long.assign(field=long.field.map(lambda f: avnames.get(_finfield(f), f)))
    df = long.pivot_table(index='fiscal_date', columns='field', values='value', aggfunc='last')
    df = df.sort_index(ascending=False).reset_index()
    df['fiscalDateEnding'] = pd.to_datetime(df['fiscal_date']).dt.strftime('%Y-%m-%d')
    for c in avnames.values():
        if c not in df.columns: df[c] = 0.0
        # AV delivered every value as a string ('None' when missing); keep that shape
        df[c] = df[c].map(lambda v: 'None' if pd.isna(v) else repr(float(v))).astype(object)
    return df

def finstmt(sym):
    #### Income stmt
    data = _finreports(sym, 'income')   # CHANGED: was requests.get(alphaurl ... INCOME_STATEMENT)
    if len(data) == 0: return pd.DataFrame()
    df = data
    df = df.replace('None', "0")
    df.fillna("0", inplace=True)
    df['asof'] = df.fiscalDateEnding
    df['revenue'] = df.totalRevenue.astype(float)/1000000
    df['revenue'] = df.revenue.rolling(4).sum().shift(-3)
    df['grosspnl'] = df.grossProfit.astype(float)/1000000
    df['grosspnl'] = df.grosspnl.rolling(4).sum().shift(-3)
    df['netIncome'] = df.netIncome.astype(float)/1000000
    df['netIncome'] = df.netIncome.rolling(4).sum().shift(-3)
    df = df[["asof", "revenue", "grosspnl", "netIncome"]]
    #### Balance sheet
    df1 = _finreports(sym, 'balance')   # CHANGED: was requests.get(alphaurl ... BALANCE_SHEET)
    if len(df1) == 0: return pd.DataFrame()
    df1 = df1.replace('None', "0")
    df1 = df1.replace('NaN', "0")
    df1.fillna(0, inplace=True)
    df1['asof'] = df1.fiscalDateEnding
    df1['assets'] = df1.totalAssets.astype(float)/1000000
    df1['liabilities'] = df1.totalLiabilities.astype(float)/1000000
    df1['equity'] = df1.totalShareholderEquity.astype(float)/1000000
    df1['cash'] = df1.cashAndShortTermInvestments.astype(float)/1000000
    df1 = df1[["asof", "assets", "liabilities", "equity", "cash"]]
    df = pd.merge(df,df1,on='asof',how='inner')
    df = df.astype(object)   # CHANGED: pandas>=3 refuses fillna("0") on float columns (older pandas upcast to object)
    df.fillna("0", inplace=True)
    return df


def yquote(sym):
    # CHANGED: was a Yahoo HTML scrape; now quote.live. Same (sym, price, earn) tuple,
    # earn 'YYYY-MM-DD' or '9999-99-99' when unknown.
    q = api.quote_live(sym)
    earn = q.get('next_earnings') or '9999-99-99'
    price = float(q.get('spot_price') or 0)
    return (sym,price,earn)

def spotvol(sym,df):
    q = qryspot(sym)
    res = {'volatility': q.spotvol.max(), 'next_earnings_date':'9999-99-99' }
    if res['volatility'] == 0:
        res['volatility'] = str(round(df.histvol.max(),2))
    else:
        dt = q.earn.astype('int').astype('str').max()
        if len(dt) == 8: dt = dt[0:4]+'-'+dt[4:6]+"-"+dt[6:]
        res['next_earnings_date'] = dt
    return res

def clip(opt, cbo = None):
    if (opt == 'git'):
        q = "git stash push -m 'message'\ngit stash list\ngit stash pop stash@{n}\ngit stash branch <branchname> [<stash>]"
        copy(q)
    elif (opt == 'df'):
        #df=pd.DataFrame({'key':[1,2,2,3,3,3,4,4,4,4],'val':[4,0,2,3,4,5,7,2,7,5]})
        q  = "Filter1     - df.query('gross_bal>1e5 & tenure<30')\n"
        q += "Filter2     - df=df[df.cust_key_col.isin(cust_list) & df.name.notnull()]\n"
        q += "Feature     - df['col2']=df.apply(func or lamdba row: 1 if row.col1 is not None else 0, axis=1)\n"
        q += "Grouping    - use agg, apply(gdf2serfunc) and df.col.transform(ser2serfunc) \n"
        q += "  df.groupby('key')['val'].agg(['mean', ('sumoftran',func or lambda gdf: gdf.sum())]).reset_index()\n"
        q += "  df['diffs'] = df.groupby(['key'])['val'].diff() \n"
        q += "  def ser2val(pct,aseries): return aseries.quantile(pct) \n"
        q += "  gdf2ser = lambda gdf: gdf.val.diff().rename('myval') \n"
        q += "  gdf2ser = lambda gdf: pd.Series([gdf.val.max()]*gdf.shape[0]).rename('myval') \n"
        q += "  gdf2ser = lambda gdf: gdf.groupby(['key']).val.transform(lambda ser: ser2val(0.5,ser)).rename('myval') \n"
        q += "  aseries = df.groupby(['key']).apply(gdf2ser).reset_index().myval \n"
        q += "  df[df.val > aseries] or df['myval'] = aseries \n"
        q += "Ordering    - df.sort_values(['a', 'b'], ascending=(False, True))\n"
        q += "count_dist  - df['dep_bal'].dropna().value_counts()[df['dep_bal'].dropna().value_counts()==1] #dropnull-distinct-count-filter \n"
        q += "unqiue_chk  - df['gross_bal'].is_unique\n"
        q += "join        - pd.merge(df,df2,[left/right_]on='cname',how='inner')\n"
        copy(q)
    else: copy(opt)

class dftable:
    def __init__(self, adf):
        self.df = adf
        self.qry = None
        self.col = None

    def show(self):
        df = self.df
        if self.qry is not None:
            df = df.query(self.qry)
        if self.col is not None:
            df = df[self.col.split(",")]
        return df

    def cols(self,flds):
        self.col = flds

    def sort(self,flds,asc=True):
        self.df = self.df.sort_values(flds.split(","),ascending=asc)
        return self

    def filter(self,qry):
        self.query = qry
        return self

class Analyzer:

    def __init__(self, tkr):
        self.ticker = tkr
        df1 = loadts(tkr)
        df = tsmetricsdata(tkr, "MKTPX,OPTVOL")
        df['vol'] = df[tkr+"/OPTVOL"]/100.0
        vol = df.tail(100).vol.mean()
        df['lastpx'] = df[tkr+"/MKTPX"]
        df['prevlastpx'] = df.lastpx.shift(1)
        df['mvavg'] = df.lastpx.rolling(21).mean()
        df['mvstd'] = df.lastpx.rolling(21).std()
        df['bbhigh'] = df['mvavg'] + 2 * df['mvstd']
        df['bblow'] = df['mvavg']  - 2 * df['mvstd']
        df = pd.merge(df,df1,on='asof',how='inner')
        ## adv%
        advm = df.tail(100).adv.mean()
        df['adv%'] = df.adv/advm
        ##
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
        self.df = df
