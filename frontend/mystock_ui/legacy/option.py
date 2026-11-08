#!python
import numpy as np
import pandas as pd
from .plots import *
from .optionprice import Option
from  datetime import date
#from myutils import stock_screener
#import webbrowser as wb
from .utils import *
from .svcreader import *
import datetime
#import pylab as pl

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)
pd.set_option('display.expand_frame_repr', False)
pd.set_option('display.float_format', '{:.2f}'.format)
pd.set_option('display.max_colwidth', None)


def fdelta_dte_limit(ymd, exprymd):
    ''' returns safe dte to use for creating IC strike offsets based on actual dte '''
    dte = datediff(exprymd) - datediff(ymd)
    return int(20 + dte * 25.0 / 45.0)
    #if dte < 21: return 21
    #elif dte < 30: return 30
    #else: return 45

def ic_risk_vol(df,ivol):
    ''' should be at least 10 pts more than optvol and higher than histvol and ivol '''
    return max(ivol/100.0, 0.10 + max(df.optvol.max(),df.histvol.max())/100.0)
    #return max((df.optvol.max()+10.0)/100.0, ivol, df.histvol.max()/100.0)

def get_ic_min_width(last,vol):
    ''' returns smallest width to get to, before starting to close the shorts, during IC management '''
    if vol < 0.20: ## low vol
        width = 10
        if last < 400: width  = 8
        if last < 300: width  = 6
        if last < 200: width  = 4
        if last < 100: width  = 2
    elif vol < 0.33: ## mid vol
        width = 15
        if last < 400: width  = 12
        if last < 300: width  = 9
        if last < 200: width  = 6
        if last < 100: width  = 3
    else: ## high vol
        width = 20
        if last < 400: width = 16
        if last < 300: width = 12
        if last < 200: width = 8
        if last < 100: width = 4
    return width

def expected_move(ivol, dt = 1):
    ''' Returns expected pct-chg for given IV and days to go '''
    return ivol * pow(dt/365.0, 0.5)

def iv_to_dte(iv):
    ''' Returns approx initial dte for vertical spreads for given IV '''
    # 20 - 55 days
    # 27 - 30 days
    # 36 - 17 days
    # 45 - 11 days
    # 60 - 6  days
    if iv < 20: return 60
    if iv < 27: return int(30+(55-30)*(iv-20)/(27-20))
    if iv < 36: return int(17+(30-17)*(iv-27)/(36-27))
    if iv < 45: return int(11+(17-11)*(iv-36)/(45-36))
    if iv < 60: return int(6+(11-6)*(iv-45)/(60-45))
    return 6

def strike_approx(fdelta, lvd):
    d = delta('call', lvd.last, lvd)
    res = lvd.last
    for incr in range(200):
        if fdelta > d:
            d = delta('call', lvd.last-incr, lvd)
            #print(d,fdelta,lvd.last-incr, incr)
            res = lvd.last-incr
            if fdelta < d: return res
        else:
            d = delta('call', lvd.last+incr, lvd)
            #print(d,fdelta,lvd.last+incr, incr)
            res = lvd.last+incr
            if fdelta > d: return res
    return res

def expectedspread(df, px, vol, d, delta):
    #print(px, vol, d, delta)
    if d == 0:
        return { "days": 0, "epxlow": px, "epx": px, "epxhigh": px,
            "sprlow": px, "sprhigh": px  }
    strike = strike_approx(delta, ovar(px,vol,d))
    spr = strike - px
    spr1 = vol * pow(d/252,0.5) * 3 * px
    return { "days": d, "epxlow": px-spr1, "epx": px, "epxhigh": px+spr1,
            "sprlow": px-spr, "sprhigh": px+spr }

def tailrisk(df, px, vol, d, delta):
    if d == 0:
        return { "days": 0, "tup": 0, "tdown": 0, "touch": 0, "xup": 0, "xdown": 0  }
    df1 = df.head(100+d)
    tup, tdown, touch, up, down, cnt = 0, 0, 0, 0, 0, 100
    spr = strike_approx(delta, ovar(px,vol,d)) - px
    ohlc = {'n': lambda x: x.size,'h': np.max,'l':np.min,'o':lambda x: x.iloc[-1], 'c':lambda x: x.iloc[0]}
    for t in range(100):
        dict = df1.tail(-1-t).head(d).lastpx.agg(ohlc)
        if dict['o'] - dict['l'] > spr: tdown += 1
        if dict['h'] - dict['o'] > spr: tup += 1
        if max(dict['o'] - dict['l'],dict['h'] - dict['o']) > spr:
            touch += 1
        if dict['o'] - dict['c'] > spr: down += 1
        if dict['c'] - dict['o'] > spr: up += 1
        cnt = min(cnt,dict['n'])
    return { "days": cnt, "spr":spr, "tup": tup, "tdown": tdown,
            "touch": touch ,"xup": up, "xdown": down  }

def optcheck(tick, lvd, fdelta, func):
    df = ovar.read(tick)[['asof','vol','lastpx']]
    df = df.sort_values('asof',ascending=False)
    df1 = df.sort_values('asof',ascending=True).tail(lvd.dt)
    df1 = df1.copy().reset_index()
    if lvd.last == 0: lvd.last = df1.head(1).lastpx.max()
    if lvd.vol == 0:  lvd.vol  = df1.head(1).vol.max()
    res = pd.DataFrame()
    incr = lvd.dt//20 if lvd.dt >= 20 else 1 
    for d in range(21):
        row = func(df, lvd.last, lvd.vol, d*incr, 50-fdelta)
        for e in range(incr):
            res = append_row(res,row)
    return res.join(df1)

# def optcheck1(op, fdelta, func, days=60):
#     # Freq of tailrisks (2.5*stdev) observed for different timespans
#     df = op.df.sort_values('asof',ascending=False)[['lastpx']]
#     res = pd.DataFrame()
#     for d in range(days):
#         res = res.append(func(df,op.entry.last,op.entry.vol,d,fdelta), ignore_index=True)
#     return res

# Days offset from today for a ymd date
def datediff(dt):
    d2 = date(dt//10000,dt//100%100,dt%100)
    return (d2-date.today()).days

def reldate(days=0):
    return int(str(date.today()+datetime.timedelta(days=days)).replace('-',''))

def price(type, strike, lvd):
    days = lvd.dt if lvd.dt < 3650 else datediff(lvd.dt)
    if days <= 0 and type == 'call': return max(0,lvd.last-strike)
    if days <= 0 and type == 'put': return max(0,strike-lvd.last)
    return Option(european=False, kind=type, \
        s0=lvd.last, k=strike, r=0.050, t=days, dv=0, \
        sigma=lvd.vol).getPrice(method='BT',iteration=100)

def ivol(optpx, lastpx, strike, type, days):
    lvd = ovar(lastpx,0.20,days)
    px = price(type,strike,lvd)
    while px < optpx:
        lvd.vol += 0.01
        px = price(type,strike,lvd)
    while px > optpx:
        lvd.vol -= 0.01
        px = price(type,strike,lvd)
    return lvd.vol

def theta(type, strike, lvd):
    ''' Theta is abolute value '''
    if strike == 0: return 0
    days = lvd.dt if lvd.dt < 3650 else datediff(lvd.dt)
    pxu = price(type, strike, ovar(lvd.last,lvd.vol,days+1))
    pxd = price(type, strike, ovar(lvd.last,lvd.vol,days-1))
    return (pxu-pxd)*100/2

def delta(type, strike, lvd):
    pxu = price(type, strike, ovar(1.02*lvd.last,lvd.vol,lvd.dt))
    pxd = price(type, strike, ovar(0.98*lvd.last,lvd.vol,lvd.dt))
    if strike == 0: return 100
    return (pxu-pxd)*100/(0.04*lvd.last)

def gamma(type, strike, lvd):
    if strike == 0: return 0
    d1 = delta(type, strike, lvd)
    lvd.last += 1
    d2 = delta(type, strike, lvd)
    return d2-d1

## fdelta=Distance of strike from spotpx.
# Returns -ive for lower strikes and +ive for higher strikes
def fdelta(strike, last, vol, ymd_or_dte):
    #if strike == 0: return 50
    return round(-50+delta('call',last,ovar(strike,vol,ymd_or_dte)),2)

# strike measured in delta -50 to +50
def putstrike(fdelta, lvd, res=0.01):
    fdelta = fdelta - 50
    minv = max(0,lvd.last-500)
    maxv = lvd.last+1000.0
    return root2decimal(minv, maxv,
        lambda x: delta('put', x, ovar(lvd.last,lvd.vol,lvd.dt)) - fdelta, res)

# strike measured in delta -50 to +50
def callstrike(fdelta, lvd, res=0.01):
    fdelta = 50 - fdelta
    return root2decimal(max(0,lvd.last-100),lvd.last+1000.0,
        lambda x: delta('call', x, ovar(lvd.last,lvd.vol,lvd.dt)) - fdelta, res)

def strike(fdelta, lvd):
    #putp = 2.0 * lvd.last - putstrike(fdelta, lvd)
    callp = callstrike(fdelta, lvd)
    return callp

'''
    #### Find condor width that yields certain given delta ###
    def icroot(tick, stgy, lvd, tmpl, delt, opx):
        o = mleg(tick,lvd.dt)
        o.set(lvd.last,lvd.vol)
        def afunc(w):
            fdels = tmpl.format(delt,w).split(",")
            off1 = 0 if len(fdels) < 1 else Stgy.strike(fdels[0],o.curr)
            off2 = 0 if len(fdels) < 2 else Stgy.strike(fdels[1],o.curr)
            off3 = 0 if len(fdels) < 3 else Stgy.strike(fdels[2],o.curr)
            off4 = 0 if len(fdels) < 4 else Stgy.strike(fdels[3],o.curr)
            o.init(stgy, off1 , off2, off3, off4)
            return opx - o.price()
        return afunc

        def stgypx(mlegobj, stgy, tmpl, dte, d, w):
                o.expr = dte
                o.curr.dt = dte
                o.entry.dt = dte
                fdels = tmpl.format(d,w).split(",")
                off1 = 0 if len(fdels) < 1 else Stgy.strike(fdels[0],o.curr)
                off2 = 0 if len(fdels) < 2 else Stgy.strike(fdels[1],o.curr)
                off3 = 0 if len(fdels) < 3 else Stgy.strike(fdels[2],o.curr)
                off4 = 0 if len(fdels) < 4 else Stgy.strike(fdels[3],o.curr)
                o.init(stgy, off1 , off2, off3, off4)
                return o.price()
'''

def icpricer(stk):
    o = mleg(stk,45)
    import pandas as pd
    df = pd.DataFrame()
    for dte in [3,7,15,30,45]:
        o.expr = o.curr.dt = o.entry.dt = dte
        for d in [15,20,25,30,35]:
            cd = callstrike(d,o.curr,1)
            pd = putstrike(d,o.curr,1)
            for w in [1,2,3,5,10,15,25]:
                o.init(Stgy.peg_condor, pd-w, pd, cd, cd+w)
                ic = o.price()
                o.init(Stgy.bull_credit, pd-w, pd, 0, 0)
                pc = o.price()
                o.init(Stgy.bear_credit, cd, w+cd, 0, 0)
                cc = o.price()
                df = append_row(df,{"dte":dte,"delta":d,"width":w,"pc":pc,"cc":cc,"ic":ic})
    return df

# A snapshot of option pricing input vars
class ovar:

    # default represents expiry or maturity
    def __init__(self, last, vol = 0.01, dt = 1):
        self.last = last
        self.vol  = vol
        self.dt   = dt if dt < 20000000 else datediff(dt)

    def __str__(self):
        s = ""
        s += "px={:.2f}".format(self.last)
        s += ",vol={:.2f}".format(self.vol)
        s += ",dt=" + str(self.dt)
        return s

    @staticmethod
    def read(tkr):
        p = mleg.screener.df.query("ticker == @tkr").lastpx.max()/100.0
        df = tsmetricsdata(tkr, "MKTPX,OPTVOL")
        df['vol'] = df[tkr+"/OPTVOL"]/100.0
        df['lastpx'] = df[tkr+"/MKTPX"] * p
        return df

    @staticmethod
    def load(df, expr, ymd=0):
        # set imp_vol = last_qtr_hist_vol
        vol = df.tail(90).vol.mean()
        if ymd == 0:
            df = df.tail(1)
            ymd = int(str(df.iloc[0,0]).split()[0].replace('-',''))
        dtobj = pd.Timestamp(datetime.date(ymd//10000,ymd//100%100,ymd%100))   # CHANGED: pandas>=2 no longer matches datetime64 against date
        adf = df.query("asof == @dtobj")
        dt = datediff(expr) - datediff(ymd)
        return ovar(adf.lastpx.max(),vol,dt)

# A single option leg
class oleg:

    def __init__(self, type, buysell, strike, exprymd, ratio):
        self.type = type
        self.strike = strike
        self.exprymd =  exprymd
        self.buysell = buysell
        self.ratio = ratio

    def price(self, lvd):
        return price(self.type, self.strike, lvd)

    def gamma(self, lvd):
        return gamma(self.type, self.strike, lvd)

    def delta(self, lvd):
        return delta(self.type, self.strike, lvd)

    def theta(self, lvd):
        return theta(self.type, self.strike, lvd)

    def legtheta(self, lvd):
        return -1 * self.buysell * self.ratio * self.theta(lvd)

    def leggamma(self, lvd):
        return -1 * self.buysell * self.ratio * self.gamma(lvd)

    def legdelta(self, lvd):
        return -1 * self.buysell * self.ratio * self.delta(lvd)

    def legprice(self, lvd):
        return self.buysell * self.ratio * self.price(lvd)

    ''' adjust vol in lvd object based on input opt price '''
    def setvol(self, optpx, lvd):
        px = self.price(lvd)
        while px < optpx:
            lvd.vol += 0.01
            px = self.price(lvd)
        while px > optpx:
            lvd.vol -= 0.01
            px = self.price(lvd)

    def __str__(self):
        s = ""
        s += "Buy" if self.buysell == 1 else 'Sell'
        s += " {:.2f}".format(self.strike) + " " + self.type + " expr=" + str(self.exprymd) + " "
        # l = lvd()
        # s += "{:.2f}".format(self.legp(self.type[idx], self.strike[idx]))
        # s += "\n"
        return s

# Defines templates and default expiry (in delta) for various vertical strategies
# Template should specify legs in deterministic sequence: (a) low to high strikes,
# and (b) front to back expiry and then (c) type - call or put
#---
# Convention Strike = +20d or -20d or +20 or -20 or 360.
# Delta or Price scale, sign/no-sign => relative or absolute
# Delta scale (- => putdelta, + => calldelta)
# Deterministic sequence: (a) low-to-high strikes
class Stgy:

    ### same as collar with a netprice ###
    bear_luld    = "bear_luld"
    bull_luld    = "bull_luld"

    bear_collar  = "bear_collar"
    bear_stock   = "bear_stock"
    bear_zebra   = "bear_zebra"
    bear_credit  = "bear_credit"
    bear_debit   = "bear_debit"
    bear_bwb     = "bear_bwb"
    bear_put     = "bear_put"
    bear_call    = "bear_call"

    bull_put     = "bull_put"
    bull_call    = "bull_call"
    bull_collar  = "bull_collar"
    bull_stock   = "bull_stock"
    bull_zebra   = "bull_zebra"
    bull_credit  = "bull_credit"
    bull_debit   = "bull_debit"
    bull_bwb     = "bull_bwb"

    peg_butterfly =  "peg_butterfly"
    peg_strangle  =  "peg_strangle"
    peg_straddle  =  "peg_straddle"
    peg_condor    =  "peg_condor"
    peg_ironfly   =  "peg_ironfly"

    vol_butterfly =  "vol_butterfly"
    vol_strangle  =  "vol_strangle"
    vol_straddle  =  "vol_straddle"
    vol_condor    =  "vol_condor"
    vol_ironfly   =  "vol_ironfly"
    vol_zeehbs    =  "vol_zeehbs"

    @staticmethod
    def template(stgy):

        # Bearish
        if stgy == Stgy.bear_luld      : return "S1P@${0},B1P@${1}" # set as debit
        elif stgy == Stgy.bear_stock   : return "B1P@${0},S1C@${0}"
        elif stgy == Stgy.bear_collar  : return "S1P@${0},B1P@${1},S1C@${1},B1C@${2}"
        elif stgy == Stgy.bear_zebra   : return "S1P@${0},B1P@${1},B1P@${1}"
        elif stgy == Stgy.bear_credit  : return "S1C@${0},B1C@${1}"
        elif stgy == Stgy.bear_debit   : return "S1P@${0},B1P@${1}"
        elif stgy == Stgy.bear_bwb     : return "B1C@${0},S1C@${1},S1C@${1},B1C@${2}"
        elif stgy == Stgy.bear_put     : return "B1P@${0}"
        elif stgy == Stgy.bear_call    : return "S1C@${0}"

        # Bullish
        elif stgy == Stgy.bull_put     : return "S1P@${0}"
        elif stgy == Stgy.bull_call    : return "B1C@${0}"
        elif stgy == Stgy.bull_luld    : return "B1C@${0},S1C@${1}" # set as debit
        elif stgy == Stgy.bull_collar  : return "B1P@${0},B1C@${1},S1P@${1},S1C@${2}"
        elif stgy == Stgy.bull_stock   : return "B1C@${0},S1P@${0}"
        elif stgy == Stgy.bull_zebra   : return "B1C@${0},B1C@${0},S1C@${1}"
        elif stgy == Stgy.bull_credit  : return "B1P@${0},S1P@${1}"
        elif stgy == Stgy.bull_debit   : return "B1C@${0},S1C@${1}"
        elif stgy == Stgy.bull_bwb     : return "B1P@${0},S1P@${1},S1P@${1},B1P@${2}"

        # OptVol too high. Price moves to be squeezed (vol to implode)
        # Expect realized px chg to stay still/pegged (than implied)
        elif stgy == Stgy.peg_butterfly : return  "B1P@${0},S1P@${1},S1P@${1},B1P@${2}"
        elif stgy == Stgy.peg_strangle  : return  "S1P@${0},S1C@${1}"
        elif stgy == Stgy.peg_straddle  : return  "S1P@${0},S1C@${0}"
        elif stgy == Stgy.peg_condor    : return  "B1P@${0},S1P@${1},S1C@${2},B1C@${3}"
        elif stgy == Stgy.peg_ironfly   : return  "B1P@${0},S1P@${1},S1C@${1},B1C@${2}"

        # OptVol too low. Price moves to pickup (vol to explode)
        # Expect realized px chg to move (than implied)
        elif stgy == Stgy.vol_butterfly : return  "S1P@${0},B1P@${1},B1P@${1},S1P@${2}"
        elif stgy == Stgy.vol_strangle  : return  "B1P@${0},B1C@${1}"
        elif stgy == Stgy.vol_straddle  : return  "B1P@${0},B1C@${0}"
        elif stgy == Stgy.vol_condor    : return  "S1P@${0},B1P@${1},B1C@${2},S1C@${3}"
        elif stgy == Stgy.vol_ironfly   : return  "S1P@${0},B1P@${1},B1C@${1},S1C@${2}"
        elif stgy == Stgy.vol_zeehbs    : return  "B4C@${0},B1P@${1},S3C@${1}"
        return ""

    @staticmethod
    def strike(pxt, lvd, round = 1):
        #### pxt = +20d or -20d-10 or +20 or -20 or 360 ####
        if pxt[-1] == 'd':
            fdel = float(pxt[0:-1])
            strike = putstrike(-1*fdel,lvd) if fdel < 0 else callstrike(fdel,lvd)
            strike = (strike//round)*round
        else:
            arr = pxt.split('d')
            if len(arr) == 2:
                fdel = float(arr[0])
                px = putstrike(-1*fdel,lvd) if fdel < 0 else callstrike(fdel,lvd)
                strike = (px//round)*round + float(arr[1])
            elif pxt[0] == '+' or pxt[0] == '-':
                strike = (lvd.last//round)*round + float(arr[0])
            else:
                strike = float(arr[0])
        return strike

    @staticmethod
    def init(stgy):
        if stgy == "vol_condor":        return "-30d-10,-30d,30d,30d+10"
        elif stgy == "peg_condor":      return "-30d-10,-30d,30d,30d+10"
        elif stgy == "vol_ironfly":     return "-10d,+0,10d"
        elif stgy == "vol_butterfly":   return "-10d,+0,10d"
        elif stgy == "vol_strangle":    return "-10d,10d"
        elif stgy == "vol_straddle":    return "+0"
        elif stgy == "vol_zeehbs":      return "-20d,+0"
        elif stgy == "peg_ironfly":     return "-10d,+0,10d"
        elif stgy == "peg_butterfly":   return "-10d,+0,10d"
        elif stgy == "peg_strangle":    return "-35d,35d"
        elif stgy == "peg_straddle":    return "+0"
        elif stgy == "bear_bwb":        return "-40d,-30d,-10d"
        elif stgy == "bull_bwb":        return "10d,30d,40d"
        elif stgy == "bear_luld":       return "-5,+5"
        elif stgy == "bear_collar":     return "-5,+0,+5"
        elif stgy == "bear_stock":      return "+0"
        elif stgy == "bear_zebra":      return "+0,20d"
        elif stgy == "bull_credit":     return "-30d-10,-30d"
        elif stgy == "bear_credit":     return "30d,30d+10"
        elif stgy == "bear_debit":      return "-30d-10,-30d"
        elif stgy == "bull_debit":      return "30d,30d+10"
        elif stgy == "bear_put":        return "-30d"
        elif stgy == "bear_call":       return "30d"
        elif stgy == "bull_luld":       return "-5,+5"
        elif stgy == "bull_collar":     return "-5,+0,+5"
        elif stgy == "bull_zebra":      return "-20d,+0"
        elif stgy == "bull_stock":      return "+0"
        elif stgy == "bull_put":        return "-30d"
        elif stgy == "bull_call":       return "30d"
        return "0,0,0,0"

class Stgy2:
    # Directionally bearish
    bear_luld    = "S1P@-{0},B1P@+{0}" # set as debit
    bear_stock   = "B1P@{0},S1C@{0}"
    bear_credit1 = "S1C@+{0},B1C@+{1}"
    bear_debit1  = "B1P@-{0},S1P@-{1}"

    bear_bwb_    = "B1C@{0},S1C@{1},S1C@{1},B1C@{2}"

    bear_put     = "B1P@-{0}"
    bear_call    = "S1C@+{0}"

    # Directionally bullish
    bull_put     = "S1P@-{0}"
    bull_call    = "B1C@+{0}"

    bull_luld    = "B1C@-{0},S1C@+{0}" # set as debit
    bull_stock   = "B1C@{0},S1P@{0}"
    bull_credit1 = "S1P@-{0},B1P@-{1}"
    bull_debit1  = "B1C@+{0},S1C@+{1}"

    bull_bwb_    = "B1P@{0},S1P@{1},S1P@{1},B1P@{2}"

    # OptVol too high. Price moves to be squeezed (vol to implode)
    # Expect realized px chg to stay still/pegged (than implied)
    peg_butterfly =  "B1P@-{0},S1P@0,S1P@0,B1P@+{0}"
    peg_strangle1 =  "S1C@{0},S1P@{1}"
    peg_straddle  =  "S1C@{0},S1P@{0}"
    peg_condor1   =  "B1P@-{1},S1P@-{0},S1C@+{0},B1C@+{1}"
    peg_ironfly   =  "B1P@-{0},S1P@0,S1C@0,B1C@+{0}"

    # OptVol too low. Price moves to pickup (vol to explode)
    # Expect realized px chg to move (than implied)
    vol_butterfly =  "S1P@-{0},B1P@0,B1P@0,S1P@+{0}"
    vol_strangle1 =  "B1C@{0},B1P@{1}"
    vol_straddle  =  "B1C@{0},B1P@{0}"
    vol_condor1   =  "S1P@-{1},B1P@-{0},B1C@+{0},S1C@+{1}"
    vol_ironfly   =  "S1P@-{0},B1P@0,B1C@0,S1C@+{0}"

    @staticmethod
    def set(stgy, opt):
        spot = opt.entry.last
        if stgy == Stgy2.bear_credit1 or \
            stgy == Stgy2.bull_credit1 or \
            stgy == Stgy2.peg_condor1 or \
            stgy == Stgy2.peg_butterfly:
            print("fdels=35,40,0")
            opt.init(stgy,strike(35, opt.entry) - spot,
                strike(40, opt.entry) - spot)
        if stgy == Stgy2.bear_debit1 or \
            stgy == Stgy2.bull_debit1 or \
            stgy == Stgy2.bull_luld or \
            stgy == Stgy2.bear_luld or \
            stgy == Stgy2.bull_stock or \
            stgy == Stgy2.bear_stock or \
            stgy == Stgy2.peg_ironfly or \
            stgy == Stgy2.vol_condor1 or \
            stgy == Stgy2.vol_butterfly:
            print("fdels=10,15,0")
            opt.init(stgy,strike(10, opt.entry) - spot,
                strike(15, opt.entry) - spot)
        if stgy == Stgy2.peg_straddle or stgy == Stgy2.vol_straddle:
            print("fdels=0,0,0")
            opt.init(stgy,0,0)
        if stgy == Stgy2.vol_strangle1 or stgy == Stgy2.peg_strangle1:
            print("fdels=-5,5,0")
            opt.init(stgy,strike(-5, opt.entry)-spot,strike(+5, opt.entry)-spot)
        if stgy == Stgy2.bear_bwb_:
            print("fdels=-40,-30,-10")
            opt.init(stgy, strike(-40, opt.entry)-spot,
                strike(-30, opt.entry)-spot, strike(-10, opt.entry) - spot)
        if stgy == Stgy2.bull_bwb_:
            print("fdels=10,31,40")
            opt.init(stgy, strike(10, opt.entry)-spot,
                strike(31, opt.entry)-spot, strike(40, opt.entry)-spot)
        if stgy == Stgy2.bear_put or \
            stgy == Stgy2.bull_put or \
            stgy == Stgy2.bear_call or \
            stgy == Stgy2.bull_call:
                print("fdels=30,0,0")
                opt.init(stgy,strike(30,opt.entry) - spot, 0)

class mleg:

    screener = None

    #Example legs=BC@+2.3,SP@+5.4
    # iymd can only be past date in ymd or 0 for latest day
    def __init__(self, tkr, dte, iymd = 0):
        self.expr = reldate(dte)
        # if edtymd < 3650:
        #     self.expr = int(str(date.today()+datetime.timedelta(days=edtymd)).replace('-',''))
        self.ticker = tkr.upper()
        self.df = ovar.read(self.ticker)
        self.indt = iymd if iymd != 0 else int(str(self.df['asof'].max()).replace('-','').split()[0])
        self.entry = ovar.load(self.df,self.expr,self.indt)
        self.curr  = ovar.load(self.df,self.expr,0)
        self.olegs = []

    def set(self, upx, vol):
        if upx <= 0: upx = self.curr.last
        if vol <= 0: vol = self.curr.vol
        self.curr.vol = vol
        self.curr.last = upx
        if self.curr.dt == self.entry.dt:
            self.entry.vol = vol
            self.entry.last = upx
        return self

    # args are strike offsets to spotpx in the expected direction of the strategy and ascending in abs value
    # Exceptions like synthetic_stock or strangle can support offsets in both direction (+/- sign)
    def init(self, name, arg0 = 0, arg1 = 0, arg2 = 0, arg3 = 0):
        if hasattr(Stgy, name):
            name = getattr(Stgy, name)[0]
        legs = name.format(arg0,arg1,arg2,arg3)
        self.olegs = []
        for leg in legs.upper().split(","):
            bs = 1.0 if leg[0:1] == 'B' else -1.0
            ratio = int(leg[1:2])
            type = "call" if leg[2:3] == 'C' else 'put'
            strike = float(leg[5:]) if leg[4:5] == '$' else self.entry.last+float(leg[4:])
            expr = int(str(date.today()+datetime.timedelta(days=self.entry.dt)).replace('-',''))
            self.olegs.append(oleg(type,bs,strike,expr,ratio))
        return self

    def invert(self):
        for leg in self.olegs:
            leg.buysell *= -1

    def ofunc(self):
        ri, rw = self.risk(), self.reward()
        px, be = self.price(-1), self.bep(0)
        return (rw*(50-be))/ri if px < 0 else (rw*be)/ri

    # Finds most reward for least risk for a strategy
    # Tries various strike/maturity offsets from the spot price
    def optimize(self, name, off, lmt, incr, wks):
        maxexprd, maxrr, maxargs0, maxargs1 = 0, 0, 0, 0
        cnt = (lmt - off)//incr + 1
        iters = 0
        for wk in range(wks):
            self.entry.dt = (wk+1)*7
            for args0 in range(cnt):
                for args1 in range(cnt):
                    if args0 >= args1:
                        continue
                    iters += 1
                    self.init(name, off+incr*args0, off+incr*args1)
                    #print(args0*incr, args1*incr, wk, d)
                    rr = self.ofunc()
                    if rr > maxrr:
                        maxrr = rr
                        maxargs0, maxargs1 = off+args0*incr, off+args1*incr
                        maxexprd = self.entry.dt
                    #print(maxrr, rr, args0, args1, wk)
        self.expr = int(str(date.today()+datetime.timedelta(days=maxexprd)).replace('-',''))
        px, vol = self.entry.last, self.entry.vol
        self.entry = ovar.load(self.df, self.expr, 0)
        self.curr  = ovar.load(self.df, self.expr, 0)
        self.entry.vol = self.curr.vol = vol
        self.entry.last = self.curr.last = px
        self.init(name, maxargs0, maxargs1)
        maxrr = self.reward()/self.risk()
        print("iters",iters,"maxrr","{:.2f}".format(maxrr),"args",maxargs0, maxargs1,'days',maxexprd)

    def theta(self):
        return sum(map(lambda leg: leg.legtheta(self.curr), self.olegs))

    def delta(self, dt = 0):
        lvd = self.entry if dt < 0 else self.curr if dt == 0 else ovar.load(self.df,self.expr,dt)
        return sum(map(lambda leg: leg.legdelta(lvd), self.olegs))

    def gamma(self, dt = 0):
        lvd = self.entry if dt < 0 else self.curr if dt == 0 else ovar.load(self.df,self.expr,dt)
        return sum(map(lambda leg: leg.leggamma(lvd), self.olegs))

    #Internal function to return mleg price for given lvd
    def lvdpx(self, lvd):
        #lvdl = ovar(lvd.last, self.vol if lvd.vol > 0.01 and self.vol > 0 else lvd.vol, lvd.dt)
        return sum(map(lambda leg: leg.legprice(lvd), self.olegs))

    def dte2opx(self, dbcr = 0):
        df = pd.DataFrame()
        days = [1,3,5,7,9,11,14,17,21,25,30,35,42,49]
        if dbcr == 0:
            dbcr = self.lvdpx(ovar(self.curr.last,self.curr.vol,49))
        for day in days:
            p0 = self.lvdpx(ovar(self.curr.last,self.curr.vol,day))
            # 1 day elapsed
            p1 = self.lvdpx(ovar(self.curr.last,self.curr.vol,day-1))
            # 5 points chg in vol
            dwn5v = self.lvdpx(ovar(self.curr.last, self.curr.vol-0.01, day))
            up5v  = self.lvdpx(ovar(self.curr.last, self.curr.vol+0.01, day))
            # 3% drop in px
            dwn3p = self.lvdpx(ovar(self.curr.last * 0.99, self.curr.vol, day))
            up3p = self.lvdpx(ovar(self.curr.last * 1.01, self.curr.vol, day))
            rw = {
                        "dte":   day,
                        "opx":   "{0:.2f}".format(p0),
                        "upp":  "{0:.2f}".format(100.0*(p0-up3p)/dbcr),
                        "dwnp": "{0:.2f}".format(100.0*(p0-dwn3p)/dbcr),
                        "upv":  "{0:.2f}".format(100.0*(p0-up5v)/dbcr),
                        "dwnv": "{0:.2f}".format(100.0*(p0-dwn5v)/dbcr),
                        "dwnt": "{0:.2f}".format(100.0*(p0-p1)/dbcr),
                    }
            df = append_row(df,rw)
        return df

    def legdf(self):
        df = pd.DataFrame()
        for leg in self.olegs:
            rw = { "type":leg.type, "strike":leg.strike, "expr": str(leg.exprymd),
                "qty": leg.buysell * leg.ratio, "optprice": leg.price(self.entry),
                "optdelta": leg.delta(self.entry),
                "stkpx": self.curr.last, "stkvol": self.curr.vol }
            df = append_row(df,rw)
        df['netdelta'] = df.qty * df.optdelta
        df['netdelta'] = df.netdelta.sum()
        df['netprice'] = df.optprice * df.qty
        netpx = df.netprice.sum()
        df['netprice'] = netpx
        return df

    def price(self, dt = 0):
        lvd = self.entry if dt < 0 else self.curr if dt == 0 else ovar.load(self.df,self.expr,dt)
        return self.lvdpx(lvd)

    # Probability to attain the stock price that will break-even optpx at expiry
    # Should trend lower if mleg.px -> 0
    # Should trend higher if mleg.px < maxpx
    def bep(self, dt = 0):
        ix, ox = 0, -9999999.9
        for leg in self.olegs:
            pnl = self.lvdpx(ovar(leg.strike)) - self.price(-1)
            if pnl > ox: ix,ox = leg.strike,pnl
        lvd = self.entry if dt < 0 else self.curr if dt == 0 else ovar.load(self.df,self.expr,dt)
        #offset = sum(map(lambda leg: abs(leg.strike-lvd.last), self.olegs))/len(self.olegs)
        return delta('call',ix,lvd)

    def pnl(self, dt = 0):
        return self.price(dt) - self.price(-1)

    def __call__(self):
        print(str(self))

    def __str__(self):
        s = "Init " + str(self.entry) + "\nCurr " + str(self.curr) + "\n\n"
        for leg in self.olegs:
            s += str(leg) + "{:.2f}".format(leg.price(self.entry)) + "\n"
        s += "\n"
        s += " tradedt   = " + str(self.indt) + "\n"
        s += " expiry    = " + str(self.expr) + "\n\n"
        s += " risk      = {:.2f}\n".format(self.risk())
        s += " reward    = {:.2f}\n".format(self.reward())
        rrr = self.reward()/self.risk() if self.risk() > 0 else 0
        s += " rrratio   = {:.2f}\n\n".format(rrr)
        s += " priceorig = {:.2f}\n".format(self.price(-1))
        s += " pricenow  = {:.2f}\n".format(self.price(0))
        s += " pnlnow    = {:.2f}\n\n".format(self.pnl(0))
        s += " beporig   = {:.2f}\n".format(self.bep(-1))
        s += " bepnow    = {:.2f}\n".format(self.bep(0))
        s += " netdelta  = {:.2f}\n".format(self.delta(0))
        s += " netgamma  = {:.2f}\n".format(self.gamma(0))
        return s

    def risk(self):
        pxs = [0.1 * self.entry.last, 10 * self.entry.last, self.entry.last,
                0.1 * self.curr.last, 10 * self.curr.last, self.curr.last]
        return self.price(-1) - min(map(lambda px: self.lvdpx(ovar(px)), pxs))

    def reward(self):
        pxs = [0.1 * self.entry.last, 10 * self.entry.last, self.entry.last,
                0.1 * self.curr.last, 10 * self.curr.last, self.curr.last]
        return max(map(lambda px: self.lvdpx(ovar(px)), pxs)) - self.price(-1)

    def decay(self, incr=5):
        pxl = int(self.curr.last) - 2 * incr
        dict = { 'dte': list(range(50)) }
        for inx in range(5):
            px = pxl + inx * incr
            key = 'px'+str(px)
            dict[key] = []
            for day in range(50):
                px0 = self.lvdpx(ovar(px,self.curr.vol,day))
                px1 = self.lvdpx(ovar(px,self.curr.vol,day+1))
                dict[key].append(px0)
        df = pd.DataFrame(dict).sort_values('dte',ascending=False)
        df['dte'] = df.dte.astype('string')
        return graph.line(df,'dte',",".join(list(df.columns)[1:]), "Theta decay chart")


    def plot(self):
        legdf = self.legdf()
        incr = np.ceil(0.02*self.curr.last)
        incrmax = int(1+np.ceil((legdf.strike.max()-legdf.strike.min())*0.5/incr))

        dict = {"upx":[], "expiry":[], "d02":[], "d05":[]}
        dts = [1,3]
        for day in range(20):
            if day > 0 and 7*day < self.curr.dt:
                dts.append(7*day)
        for dt in dts:
            dict['d'+str(dt)] = []
        dict["curr"] = []

        for l in range(-5*incrmax,incrmax+10):
            ux = int(self.curr.last)+l*incr
            dict['upx'].append(ux)
            dict['expiry'].append(self.lvdpx(ovar(ux)))
            dict['curr'].append(self.lvdpx(ovar(ux, self.curr.vol, self.curr.dt)))
            dict['d02'].append(self.lvdpx(ovar(ux, self.curr.vol/5, 1)))
            dict['d05'].append(self.lvdpx(ovar(ux, self.curr.vol/2, 1)))
            for dt in dts: dict['d'+str(dt)].append(self.lvdpx(ovar(ux, self.curr.vol, dt)))
        df = pd.DataFrame(dict)
        return graph.line(df,'upx',",".join(list(df.columns)[1:]), "Option Price chart")

    def sgrid(self):
        return self.grid(True)

    def grid(self, shrt = False):
        ''' Provides mkt value of this mleg portfolio for various mktpx/exptime chgs '''
        print("Last: ", self.curr.last, "Vol: ", "{0:.3f}".format(self.curr.vol))
        #auto-calculates px and incr
        vol = self.entry.vol
        px = max(0,min(min(map(lambda leg: leg.strike, self.olegs))-5,self.curr.last - 5))
        cnt = int(max(max(map(lambda leg: leg.strike, self.olegs)),self.curr.last) + 5 - px)
        data, index = [], []
        days = [720, 540, 360, 180, 90, 60, 45, 30, 22, 15, 7, 0]
        if shrt == True:
            days = [14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0]
        dincr = 1 if datetime.datetime.today().weekday() >= 5 else (5-datetime.datetime.today().weekday())
        days = [d+dincr for d in days]
        days.append(0)
        for l in range(cnt):
            row = {}
            index.append(px+l*1.0)
            for d in days:
                dt = datetime.date.today() + datetime.timedelta(days=d)
                key = dt.strftime('%d-%b') + '(' + str(d) + 'd)'
                row[key] = self.lvdpx(ovar(px+l*1.0, vol if d > 0 else 0.01, max(d,1)))
            data.append(row)
        return pd.DataFrame(data, index=index)

# There are 4 dimensions fdelta=0, width, spread, dte
class ic:

    def __init__(self, stk, dte = 45, fdel = 35, width = 0):
        self.stock = stk.upper()
        sdf = qryspot(self.stock)
        last, ivol = sdf.spotprice.max(), sdf.spotvol.max()/100.0
        df = mleg.screener.df.query("ticker == @self.stock")
        minvol = (5.0 + max(df.histvol.max(), df.optvol.max()))/100.0
        lvd = ovar(last,max(minvol,ivol),dte)
        self.init(lvd,fdel,width)

    def init(self, lvd, fdel=35, width = 0):
        self.dte = lvd.dt
        print(fdel, lvd, strike(fdel, lvd))
        self.spread = int(np.ceil(strike(fdel, lvd)) - np.ceil(lvd.last))
        self.width  = int(max(1.0,np.floor(strike(fdel+5, lvd)) - np.ceil(lvd.last) - self.spread))
        if width > 0: self.width = width
        print("Init lvd",str(lvd),"spread",self.spread,"width",self.width)
        self.setic(lvd.last, self.width, self.spread, self.spread, self.width).set()

    def unskew(self):
        last = float(mleg.screener.spotprice(self.stock)['c'])
        return self.setic(last, self.width, self.spread, self.spread, self.width)

    def skew(self,w1,s1,s2,w2):
        last = float(mleg.screener.spotprice(self.stock)['c'])
        w1,w2 = int(w1*self.width),int(w2*self.width)
        s1,s2 = int(s1*self.spread),int(s2*self.spread)
        return self.setic(last,w1,s1,s2,w2)

    def hard(self,p1,p2,p3,p4):
        tmpl = "B1P@${0},S1P@${1},S1C@${2},B1C@${3}".format(p1,p2,p3,p4)
        strikes = "{0},{1},{2},{3}".format(p1,p2,p3,p4)
        self.pricer = mleg(self.stock, self.dte)
        self.pricer.init(tmpl)
        print(mleg.screener("strikes",self.stock, strikes, self.dte))
        return self

    #Inputs x=width,y=dte output=extractable pct of max profit
    def maxpnls(self, sprd_del_csv):
        ''' Compare decay rate of ic with different spreads '''
        sdf = qryspot(self.stock)
        upx, ivol = sdf.spotprice.max(), sdf.spotvol.max()/100.0
        lvd = ovar(upx, ivol, self.dte)
        df = pd.DataFrame()
        df['dte'] = list(range(0,self.dte))
        for w in sprd_del_csv.split(","):
            fdel = float(w)
            fw = int(np.ceil(strike(fdel, lvd)) - np.ceil(lvd.last))
            pricer = mleg(self.stock, self.dte)
            tmpl = "B1P@${0},S1P@${1},S1C@${2},B1C@${3}".format(
                upx-self.width-fw, upx-fw, upx+fw, upx+fw+self.width)
            pricer.init(tmpl)
            maxpx = pricer.lvdpx(ovar(upx,ivol,self.dte))
            vals = []
            for dt in range(self.dte):
                px = pricer.lvdpx(ovar(upx,ivol,dt))
                vals.append(px*100/maxpx)
            df['S'+w] = vals
        graph.line(df,'dte',",".join(list(df.columns)[1:]), "Extraction vs Spreads").show()
        return df

    def maxpnl(self):
        ''' Compare decay rate of ic with different spreads '''
        ######### Width and Spread Range ######
        sprd_del_csv = "40,35,30,25,20"
        width_csv = "W5,W10,W15,W20"
        sdf = qryspot(self.stock)
        upx, ivol = sdf.spotprice.max(), sdf.spotvol.max()/100.0
        lvd = ovar(upx, ivol, self.dte)
        df = pd.DataFrame()
        df['width'] = width_csv.split(",")
        for spr in sprd_del_csv.split(","):
            vals = []
            for w in width_csv.split(","):
                fw = float(w[1:])
                fs = int(np.ceil(strike(float(spr), lvd)) - np.ceil(lvd.last))
                pricer = mleg(self.stock, self.dte)
                tmpl = "B1P@${0},S1P@${1},S1C@${2},B1C@${3}".format(
                        upx-fw-fs, upx-fs, upx+fs, upx+fs+fw)
                pricer.init(tmpl)
                vals.append(pricer.lvdpx(ovar(upx,ivol,self.dte)))
            df['S'+spr] = vals
        return df

    #Inputs x=width,y=dte output=extractable pct of max profit
    def maxpnlw(self, widthcsv):
        ''' Compare decay rate of ic with different widths '''
        sdf = qryspot(self.stock)
        upx, ivol = sdf.spotprice.max(), sdf.spotvol.max()/100.0
        df = pd.DataFrame()
        df['dte'] = list(range(0,self.dte))
        for w in widthcsv.split(","):
            fw = float(w)
            pricer = mleg(self.stock, self.dte)
            tmpl = "B1P@${0},S1P@${1},S1C@${2},B1C@${3}".format(upx-fw-self.spread,
                    upx-self.spread, upx+self.spread,upx+fw+self.spread)
            pricer.init(tmpl)
            maxpx = pricer.lvdpx(ovar(upx,ivol,self.dte))
            vals = []
            for dt in range(self.dte):
                px = pricer.lvdpx(ovar(upx,ivol,dt))
                vals.append(px*100/maxpx)
            df['W'+w] = vals
        graph.line(df,'dte',",".join(list(df.columns)[1:]), "Extraction vs Widths").show()
        return df

    def setic(self, upx, w1,s1,s2,w2):
        last = np.ceil(upx)
        print("upx w1 s1 s2 w2", last, w1, s1, s2, w2)
        tmpl = "B1P@${0},S1P@${1},S1C@${2},B1C@${3}".format(last-s1-w1,
                last-s1, last+s2,last+s2+w2)
        strikes = "{0},{1},{2},{3}".format(last-s1-w1, last-s1, last+s2, last+s2+w2)
        print(mleg.screener("strikes",self.stock, strikes, self.dte))
        self.pricer = mleg(self.stock, self.dte)
        self.pricer.init(tmpl)
        print("Reward",round(self.pricer.reward(),2),"Risk",round(self.pricer.risk(),2))
        return self

    def set(self, lvd = None):
        if lvd is None:
            sdf = qryspot(self.stock)
            last, ivol = sdf.spotprice.max(), sdf.spotvol.max()/100.0
            lvd = ovar(last, ivol, self.dte)
        self.pricer.set(lvd.last,lvd.vol)
        print("----\nlvd\t",str(lvd),"\ndbcrpx",round(self.pricer.price(),2))
        df = mleg.screener.df.query("ticker == @self.stock")
        print(df["lastpx px2bbpct prscore rangescore optvol optivol histvol volscore volratio corr".split()].transpose())

    #def setprice(self, dbcrpx, mktdir):
    #    for idx in range(int(self.spread+self.width)):
    #        incr = idx if mktdir > 0 else -idx
    #        self.skew(incr+self.lvd.last, self.spread, self.width)
    #        if self.pricer.price() - dbcrpx <= 0:
    #            break
