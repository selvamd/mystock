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
from .option import *
import datetime
#import pylab as pl

pd.set_option('display.max_rows', 500)
pd.set_option('display.max_columns', 500)
pd.set_option('display.width', 1000)
pd.set_option('display.expand_frame_repr', False)
pd.set_option('display.float_format', '{:.2f}'.format)
pd.set_option('display.max_colwidth', None)

def readpricehist(tkr):
    p = mleg.screener.df.query("ticker == @tkr").lastpx.max()/100.0
    df = tsmetricsdata(tkr, "MKTPX,OPTVOL")
    df['vol'] = df[tkr+"/OPTVOL"]/100.0
    df['lastpx'] = df[tkr+"/MKTPX"] * p
    return df

def load(df, expr, ymd=0):
    # set imp_vol = last_qtr_hist_vol
    vol = df.tail(90).vol.mean()
    if ymd == 0:
        df = df.tail(1)
        ymd = int(str(df.iloc[0,0]).split()[0].replace('-',''))
    dtobj = pd.Timestamp(datetime.date(ymd//10000,ymd//100%100,ymd%100))   # CHANGED: pandas>=2 date vs datetime64
    adf = df.query("asof == @dtobj")
    dt = datediff(expr) - datediff(ymd)
    return ovar(adf.lastpx.max(),vol,dt)

# A snapshot of option pricing input vars
class oparam:

    # default represents day
    def __init__(self, last, vol, ymd):
        self.last = last
        self.vol  = vol
        self.ymd  = ymd

    def __call__(self):
        return self.last,self.vol,self.ymd

    def __str__(self):
        s = ""
        s += "px={:.2f}".format(self.last)
        s += ",vol={:.2f}".format(self.vol)
        s += ",ymd=" + str(self.ymd)
        return s

class posleg:
    ''' Transactions done on a single symbol '''
    # symbol = IBM@strike@exprymd@B3C
    # Indicates if buy or sell to open a position (ie creates debit or credit)
    def __init__(self, key, tpos):
        arr = key.split("@")
        self.ticker = arr[0]
        self.exprymd = int('20'+arr[2])
        self.leg = oleg("call" if arr[-1][-1] == 'C' else 'put',
            1.0 if arr[-1][0:1] == 'B' else -1.0, float(arr[1]),
            self.exprymd, int(arr[-1][1:-1]))
        if tpos is None: raise Exception("No pos object provided")
        else: self.tposobj = tpos
        self.pos = pd.DataFrame()

    def fdeltadtelimit(self):
        ''' returns dte to use for creating delta limits based on actual dte left '''
        return fdelta_dte_limit(self.tposobj.lvd.ymd,self.leg.exprymd)

    def fdeltalimit(self):
        ''' returns otmness limit that will be out of reach for given spot price & dte left '''
        lvd = self.tposobj.lvd
        fdel = fdelta(self.leg.strike, lvd.last, lvd.vol, self.fdeltadtelimit())
        if self.leg.type == 'put': fdel *= -1
        return fdel

    # Checks and alerts about price or delta-levels (or moneyness)
    def deltaalert(self):
        fdel = self.fdeltalimit()
        lvd = self.tposobj.lvd
        riskvol = ic_risk_vol(mleg.screener.df.query("ticker == @self.ticker"), lvd.vol)
        dte = self.fdeltadtelimit()
        if fdel > 40.0:   msg = " Too Safe "
        if fdel < 0:      msg = " Red      "
        elif fdel < 10.0: msg = " Orange   "
        elif fdel < 25.0: msg = " Yellow.  "
        else:             msg = " Green    "
        return (msg, round(fdel,2), str(self.leg), riskvol, dte, round(self.leg.strike,2))

    # Prices and qty should be the actual values: abs traded qty and abs trade price
    def open(self, ymd, optpx, qty, upx, vol):
        bs = 'B' if self.leg.buysell > 0 else 'S'
        d = {"asof":str(ymd),"px":optpx,"qty":qty,'oc':'O','bs':bs,\
             'type': self.leg.type.upper(), 'ratio': self.leg.ratio,
             'upx': upx, 'vol': vol,\
             'strike': self.leg.strike, 'expiry': str(self.leg.exprymd)}
        self.pos = append_row(self.pos,d)
        self.pos['pxqty'] = self.pos.px * self.pos.qty
        return self

    # Prices and qty should be the actual values: abs traded qty and abs trade price
    def close(self, ymd, optpx, qty, upx, vol):
        bs = 'S' if self.leg.buysell > 0 else 'B'
        d = {"asof": str(ymd),"px":optpx,"qty":qty,'oc':'C','bs':bs,\
                'type': self.leg.type.upper(),'ratio': self.leg.ratio,\
                'upx': upx, 'vol': vol,\
                'strike': self.leg.strike, 'expiry': str(self.leg.exprymd)}
        self.pos = append_row(self.pos,d)
        self.pos['pxqty'] = self.pos.px * self.pos.qty
        return self

    def legprice(self):
        lvd = self.tposobj.lvd
        dte = datediff(self.leg.exprymd) - datediff(lvd.ymd)
        return self.leg.buysell * self.legqty() * self.leg.price(ovar(lvd.last,lvd.vol,dte))

    def legqty(self):
        ''' quantity still open '''
        closed    = -1 * self.leg.buysell * self.pos.query("oc == 'C'").qty.sum()
        opened    =  self.leg.buysell * self.pos.query("oc == 'O'").qty.sum()
        return opened+closed

    def getsimtrade(self):
        df = self.pos
        closed    = df.query("oc == 'C'").qty.sum()
        closedpx  =  0 if closed == 0 else df.query("oc == 'C'").pxqty.sum()/closed
        opened    = df.query("oc == 'O'").qty.sum()
        openedpx  =  0 if opened == 0 else df.query("oc == 'O'").pxqty.sum()/opened

        res = 'B' if self.leg.buysell == 1.0 else 'S'
        res += str(int(self.leg.strike)) + self.leg.type[0].upper()
        res += str(int(opened)) + '@' + str(round(openedpx,2))
        if closed > 0:
            res += ',S' if self.leg.buysell == 1.0 else ',B'
            res += str(int(self.leg.strike)) + self.leg.type[0].upper()
            res += str(int(closed)) + '@' + str(round(closedpx,2))
        return res

    def pnl(self):
        lvd = self.tposobj.lvd
        df = self.pos
        closed    = -1 * self.leg.buysell * df.query("oc == 'C'").qty.sum()
        opened    =  self.leg.buysell * df.query("oc == 'O'").qty.sum()
        openedpx  =  0 if opened == 0 else self.leg.buysell * df.query("oc == 'O'").pxqty.sum()/opened

        pnlrealized = self.leg.buysell * df.query("oc == 'C'").pxqty.sum()
        pnlrealized += openedpx * closed

        openqty   = opened + closed
        opencost  = openqty * openedpx
        dte = datediff(self.leg.exprymd) - datediff(lvd.ymd)
        openvalue = openqty * self.leg.price(ovar(lvd.last,lvd.vol,dte))
        # openvalue(db/cr),pnlrealized(+/-),opencost(db/cr),tradedqty,openqty(+/-)
        return (openvalue, pnlrealized, opencost, abs(2 * opened), openqty)

class tposition:

    # "login":"str", "portfolio":"str", "position":"str","trades_id":'int',
    # "symbol": "MSFT@150@230515@B1C", "tradedt":"int", "buysell":"str",
    # "px":"float", "qty":"int"
    def __init__(self, session, login, portfolio, position):
        self.login = login
        self.portfolio = portfolio
        self.position = position
        self.session = session
        self.poslegs = {}
        self.lvd = None
        self.ticker = None

    def bulktrade(self, ticker, stgy, expiry, strikes, prices, qty, param):
        ''' Using -ive sign in prices is a must if contract is sold '''
        expiry  = str(expiry).split(",")
        strikes = str(strikes).split(",")
        cpinfo = []
        for cp in str(stgy).split(","):
            cpinfo += [cp.split('@')[0]]
        prices  = str(prices).split(",")
        qty     = str(qty).split(",")

        buysell = []
        for idx in range(len(prices)):
            buysell += [ 'S' if prices[idx][0] == '-' else 'B' ]
            if len(expiry) < len(prices):  expiry += [expiry[0]]
            if len(qty) < len(prices): qty += [qty[0]]

        for idx in range(len(strikes)):
            sym = '@'.join([ticker,strikes[idx],expiry[idx][2:],cpinfo[idx]])
            self.trade(sym, buysell[idx], abs(float(prices[idx])), int(qty[idx]), param.last, param.vol, param.ymd)

    # Symbol = MSFT@150@230515@B1C
    #   => Buy 1 call of msft, strike=150 and expiry=may15 to open
    def trade(self, sym, buysell, px, qty, upx, vol, tradedt = reldate(0)):
        #tradeid = int(datetime.datetime.now().strftime('%y%m%d%H%M%S%f'))
        #d = {"login":self.login, "portfolio":self.portfolio, "position": self.position,
        #    "trades_id": tradeid, "buysell": buysell, "legsym": sym, "px": px, "qty": qty,
        #    "tradedt": tradedt, "upx": upx, "uvol": vol }
        #self.tradesdb.insert(d)
        booktrade(self.session, self.position, self.portfolio, buysell, sym, px, qty, upx, vol, tradedt)
        self.load(sym,buysell,px,qty,tradedt, upx, vol)

    def getsimtrades(self):
        res = ''
        for key, leg in self.poslegs.items():
            res += "," + leg.getsimtrade()
        return res[1:]

    #B[30d]230P10@2.18,....
    def simtrades(self, legs):
        ## expects self.ticker and self.exprdt already set
        for leg in legs.upper().split(","):
            leg, px = leg.split("@")
            cp = 'C' if 'C' in leg else 'P'
            leg, qty = leg.split(cp)
            ymd = self.exprdt
            if 'D' in leg:
                ymd = reldate(int(leg.split('D')[0][1:]))
                leg = leg[0] + leg.split('D')[1]
            sym = self.ticker + "@" + leg[1:] + "@" + str(ymd)[2:] + "@" + leg[0] + "1" + cp            
            self.load(sym, leg[0], float(px), int(qty), reldate(-2), self.lvd.last, self.lvd.vol)

    def load(self, sym, buysell, px, qty, tradedt, upx, vol):
        arr = sym.split("@")
        if self.ticker is None:
            self.ticker = arr[0].upper()
        p = self.poslegs[sym] if sym in self.poslegs else posleg(sym, self)
        self.poslegs[sym] = p
        if len(self.poslegs) == 1:
            self.qty    = int(qty/p.leg.ratio)
            self.exprdt = p.leg.exprymd
            self.opendt = tradedt
        if buysell == arr[-1][0]:
            p.open(tradedt, px, qty, upx, vol)
        else:
            p.close(tradedt, px, qty, upx, vol)

    def spot(self, live = True):
        tick = self.ticker
        df = mleg.screener.df.query("ticker == @tick")
        upx = df.lastpx.max()
        vol = df.histvol.max()/100.0
        ymd = mleg.screener.ctx.lastdt
        self.lvd = oparam(upx,vol,ymd)
        if live == True:
            sdf = qryspot(self.ticker)
            upx = float(sdf.spotprice.max())
            vol = float(sdf.spotvol.max())/100.0
            ### vol is not too reliable ####
            if vol * 100.0 < df.optvol.max():
                vol = 0.02 + df.optvol.max()/100.0
                if df.volscore.max() > -0.5: vol += 0.02
                if df.volscore.max() > 0.5:  vol += 0.03
                if df.volscore.max() > 1.5:  vol += 0.03
            ymd = reldate(0)
            #print('Live px/vol',upx,vol)
            #print('Spot', tick, upx, round(vol,2), ymd)
            self.set(upx,vol,ymd)
        return self.lvd()

    def set(self, upx = 0, ivol = 0, ymd = 0):
        if ymd <= mleg.screener.ctx.lastdt:
            self.pricehistdf = readpricehist(self.ticker)
            dtobj = pd.Timestamp(datetime.date(ymd//10000,ymd//100%100,ymd%100))   # CHANGED: pandas>=2 date vs datetime64
            df = self.pricehistdf.query("asof >= @dtobj")
            if ivol == 0: ivol = df.tail(90).vol.mean()
            if upx == 0:  upx  = df.tail(1).lastpx.max()
        if ivol > 0 and upx > 0 and ymd > 0:
            self.lvd = oparam(upx,ivol,ymd)
        return self

    def pricer(self):
        res = mleg(self.ticker, datediff(self.exprdt))
        for key, leg in self.poslegs.items():
            d = leg.pnl()
            if d[0] != 0: res.olegs.append(leg.leg)
        res.set(self.lvd.last, self.lvd.vol)
        return res

    def alert(self):
        arr = []
        for key, leg in self.poslegs.items():
            d = leg.pnl()
            if d[0] != 0: arr.append(leg.deltaalert())
        return arr


    def suggestic(self):
        openleg = None
        for key, leg in self.poslegs.items():
            if leg.pnl()[0] != 0: openleg = leg
        width = get_ic_min_width(self.lvd.last,self.lvd.vol)
        riskvol = ic_risk_vol(mleg.screener.df.query("ticker == @self.ticker"), self.lvd.vol)
        lvd = ovar(self.lvd.last,riskvol,openleg.fdeltadtelimit())
        sp = putstrike(25,lvd)
        sc = callstrike(25,lvd)
        msg = str(round(sp-width,0)) + "-" + str(round(sp,0))
        msg += "-" + str(round(lvd.last,0)) + "-"
        msg += str(round(sc,0)) + "-" + str(round(sc+width,0))
        return msg

    def suggestic_old(self):
        ''' How to manage this trade if it is ic family '''
        dict = { "lc": None, "sc": None, "lp": None, "sp": None }
        for key, leg in self.poslegs.items():
            d = leg.pnl()
            if d[0] != 0:
                if leg.leg.type == 'call' and leg.leg.buysell > 0: dict['lc'] = leg
                if leg.leg.type == 'call' and leg.leg.buysell < 0: dict['sc'] = leg
                if leg.leg.type == 'put' and leg.leg.buysell  > 0: dict['lp'] = leg
                if leg.leg.type == 'put' and leg.leg.buysell  < 0: dict['sp'] = leg
        netdelta = abs(self.greekdf('delta').netgreek.max())
        width = get_ic_min_width(self.lvd.last,self.lvd.vol)
        if netdelta < 20: return ["Donot manage"]
        msg = []

        # Evaluate width-reduction oppertutity by rolling up longs on tested side
        if dict['sc'] is not None and dict['sc'].fdeltalimit() < 20.0 and \
            (dict['lc'].leg.strike - dict['sc'].leg.strike) > (0.01+width):
                msg.append("Rollup long call width to " + str(width))
        if dict['sp'] is not None and dict['sp'].fdeltalimit() < 20.0 and \
            (dict['sp'].leg.strike - dict['lp'].leg.strike) > (0.01+width):
                msg.append("Rollup long put width to " + str(width))

        # Evaluate credit collection oppertutity by rolling up untested side
        if dict['sc'] is not None and dict['sc'].fdeltalimit() < 15.0 and \
            (dict['sp'] is None or dict['sp'].fdeltalimit() > 35.0):
                lvd = ovar(self.lvd.last,self.lvd.vol,dict['sc'].fdeltadtelimit())
                msg.append("Rollup short put to " + str(round(putstrike(25,lvd),0)))
        if dict['sp'] is not None and dict['sp'].fdeltalimit() < 15.0 and \
            (dict['sc'] is None or dict['sc'].fdeltalimit() > 35.0):
                lvd = ovar(self.lvd.last,self.lvd.vol,dict['sp'].fdeltadtelimit())
                msg.append("Rollup short call to " + str(round(callstrike(25,lvd),0)))

        # Manage by closing shorts to make it ratio spread.
        if dict['sc'] is not None and dict['sc'].fdeltalimit() < 10.0:
            msg.append("Close short calls")
        if dict['sp'] is not None and dict['sp'].fdeltalimit() < 10.0:
            msg.append("Close short puts")

        if len(msg) == 0: msg.append("Donot Manage")
        return msg

    def greekdf(self, greek):
        offset = [0,0,0,0]
        df = pd.DataFrame()
        for key, leg in self.poslegs.items():
            qty = leg.legqty()
            if qty == 0: continue
            dte = datediff(leg.leg.exprymd) - datediff(self.lvd.ymd)
            lvd = ovar(self.lvd.last,self.lvd.vol,dte)
            dval = getattr(leg.leg, greek)(lvd)   # CHANGED: was eval("leg.leg."+greek+"(lvd)")
            d = { "upx":lvd.last, "vol": lvd.vol, "expr": leg.leg.exprymd,
                    "callput": leg.leg.type,
                    "key": key, "dte": dte, "qty": qty, "optgreek": dval,
                    "leggreek": qty*dval }
            df = append_row(df, d)
            if leg.leg.type == 'put' and qty > 0: offset[0] += qty*dval
            if leg.leg.type == 'put' and qty < 0: offset[1] += qty*dval
            if leg.leg.type == 'call' and qty < 0: offset[2] += qty*dval
            if leg.leg.type == 'call' and qty > 0: offset[3] += qty*dval
        df['netgreek'] = sum(offset)
        df['plgreek'] = offset[0]
        df['psgreek'] = offset[1]
        df['csgreek'] = offset[2]
        df['clgreek'] = offset[3]
        df['type'] = greek
        return df

    def greek(self, greek):
        res = 0
        for key, leg in self.poslegs.items():
            qty = leg.legqty()
            if qty == 0: continue
            dte = datediff(leg.leg.exprymd) - datediff(self.lvd.ymd)
            lvd = ovar(self.lvd.last,self.lvd.vol,dte)
            d = getattr(leg.leg, greek)(lvd)   # CHANGED: was eval("leg.leg."+greek+"(lvd)")
            #print(str(lvd),d)
            res += qty*d
            #print(res,d*qty,key,d,qty,str(self.lvd))
        return res

    def help(self):
        print("plot(type, asof=today, incr=None) - plots metric (pnl|delta) for range of upx asof a given dates (ymds)")
        print("cone(ss) - How this stock aged since position was initiated against 1-sd benchmark")
        print("trades() - All trades done so far")
        print("pnlbylegs() - PNL breakdown realized/unrealized on individual legs")
        print("lvd() - lvd values currently used")
        print("pnl() - PNL Summary")
        print("bulktrade(ticker, stgy, expiry, strikes, prices, qty, oparam)")

    def cone(self, ss):
        asofdt = self.trades()['asof'].min()
        days = abs(datediff(int(asofdt)))
        return ss("cone",self.ticker, self.lvd.vol, 35, days)

    def plot(self, type, asofs = None, incr = 1):

        lvdsaved = self.lvd
        upx = np.floor(lvdsaved.last) - 10.0 * incr
        if asofs is None:
            asofs = str(reldate(0)) + "," + str(self.exprdt)
            #tr = self.trades()
            #asofs = str(tr['asof'].min()) + "," + str(reldate(0)) + "," + str(tr['expiry'].max())

        if type == 'pnl':
            r = self.dpnl()
            spot = r[0]-r[2]+r[1]
        elif type == 'price':
            r = self.dpnl()
            spot = r[0]
        else: spot = self.greek(type)

        df = pd.DataFrame()
        for idx in range(20):
            d = {"upx": upx+idx*incr, 'spot': spot, 'vol': self.lvd.vol }
            for asof in asofs.split(","):
                self.set(upx+idx*incr, lvdsaved.vol, int(asof))
                if type == 'pnl':
                    r = self.dpnl()
                    d[asof] = r[0]-r[2]+r[1]
                elif type == 'price':
                    r = self.dpnl()
                    d[asof] = r[0]
                else: d[asof] = self.greek(type)
            #print(str(d))
            df = append_row(df,d)

        self.set(lvdsaved.last, lvdsaved.vol, lvdsaved.ymd)
        return graph.line(df,'upx',"spot,"+asofs, "Position " + type.upper() + " plot ")


    def margin(self):
        ''' margin = Excess (short - long) from matched qty + unmatched shorts at 0 (puts) and 125% (calls) '''
        df = self.pnlbylegs()
        df['sq'] = df.strike * df.qty
        cm = df.query("type == 'call' and qty < 0").sq.sum()
        cq = df.query("type == 'call' and qty < 0").qty.sum()
        pm = df.query("type == 'put' and qty < 0").sq.sum()
        pq = df.query("type == 'put' and qty < 0").qty.sum()

        qry = "type == 'put' and qty > 0"
        for index, row in df.query(qry).sort_values("strike",ascending=False).iterrows():
            if row['qty'] <= pq:
                pq += row['qty']
                pm += row['sq']
            else:
                pm += abs(pq) * row['strike']
                pq = 0
                break
        qry = "type == 'call' and qty > 0"
        for index, row in df.query(qry).sort_values("strike").iterrows():
            if row['qty'] <= cq:
                cq += row['qty']
                cm += row['sq']
            else:
                cm += abs(cq) * row['strike']
                cq = 0
                break
        ## Use 25% margin for naked calls
        if cq < 0: cm -= (cq * df.strike.max() * 1.25)
        return max(abs(pm),cm)*100

    def trades(self):
        df = pd.DataFrame()
        for key, leg in self.poslegs.items():
            df1 = leg.pos
            df1['spot'] = leg.legprice()/leg.leg.ratio/leg.leg.buysell
            df1['supx'] = self.lvd.last
            df1['suvol'] = self.lvd.vol
            df1['spotqty'] = df1['spot'] * df1['qty']
            df = pd.concat([df,df1])
        df = df["type strike expiry ratio asof bs oc qty px spot pxqty spotqty upx vol supx suvol".split()]
        return df.sort_values("strike expiry asof".split())

    def dpnl(self, lvd = None):
        if self.lvd is None: self.spot()
        if lvd is None: lvd = self.lvd
        res = (0.0, 0.0, 0.0, 0.0, 0.0)
        for key, leg in self.poslegs.items():
            res = tuple(map(sum, zip(res, leg.pnl())))
        res = tuple(map(lambda x: round(x,2), res))
        return res

    def isclosed(self):
        df = self.pnlbylegs().query("oc == 'o'")
        return len(df) == 0

    def pnlbylegs(self):
        df = pd.DataFrame()
        for key, leg in self.poslegs.items():
            res = leg.pnl()
            d   = {}
            d['key'] = key
            d['type'] = leg.leg.type
            d['strike'] = leg.leg.strike
            d['value'] = res[0]
            d['cost']  = res[2]
            d['openpx']  = 0 if res[2] == 0 else res[2]/res[4]
            d['realized']  = res[1]
            d['qty']  = res[4]
            d['spotpx'] = 0 if res[2] == 0 else res[0]/res[4]
            d['oc'] = 'c' if res[2] == 0 else 'o'
            d['unrealized']  = 0 if res[2] == 0 else res[0]-res[2]
            df = append_row(df,d)

        d   = { 'key':'Total', 'oc':'z', 'type':'z' }
        for v in 'strike value cost openpx qty spotpx'.split(): d[v] = 0
        d['realized'] = df.realized.sum()
        d['unrealized']  = df.unrealized.sum()
        df = append_row(df,d)

        return df.sort_values('oc strike'.split(),ascending=False)

    def pnl(self):
        res = self.dpnl()
        arr = []
        arr.append({"desc":"Symbol", "value": self.ticker })
        arr.append({"desc":"Evaluation Date", "value": str(self.lvd.ymd) })
        arr.append({"desc":"Qty", "value": str(self.qty) })
        arr.append({"desc":"Expr Date", "value": str(self.exprdt) })
        arr.append({"desc":"Underlying Price", "value": str(round(self.lvd.last,2)) })
        arr.append({"desc":"Underlying Vol", "value": str(round(self.lvd.vol,2)) })
        arr.append({"desc":"Open MktValue", "value": str(round(100*res[0],2))})
        arr.append({"desc":"Price", "value": str(round(res[0]/self.qty,2))})
        arr.append({"desc":"Open Cost", "value": str(round(100*res[2],2))})
        arr.append({"desc":"Realized", "value": str(round(100*res[1],2))})
        arr.append({"desc":"Contracts Traded", "value": str(res[3])})
        pnl = 100*(res[0]-res[2]+res[1])
        arr.append({"desc":"Gross PNL($)", "value": str(round(pnl,2))})
        pnl -= 0.65*res[3]
        arr.append({"desc":"Net PNL($)", "value": str(round(pnl,2))})
        df = pd.DataFrame(arr)
        return df

    def __call__(self):
        return self.pnl()

guide = '''
    For Iron condors:
        1. Ideal: put(+) and call(-) deltas = 15-20, netdelta = 0, DTE=30-45 to balance all factors
        2. High netdelta => Direction risk to pnl (Call/bullish if imb < 0 else Put/bearish)
        3. Low netdelta + low cpoffset => Too wide spread. No decay if dte > 21.
        4. Low netdelta + high cpoffset => Too tight spread. Needs adjustments
        5. Under 7dte without cpoffset dropping to near 0 => High gamma, sudden price moves are bad
    '''
# Max delta threshold for managing excess directional risk
dte2maxdelta = [3,7,10,10,15,15,18,18,18,21,
                    21,21,21,26,26,26,26,26,26,26,
                    27,27,27,27,27,27,27,27,27,30,
                    30,30,30,30,30,30,30,30,30,30,
                    30,30,30,30,30,30,30,30,30,30]
# Min delta needed for enough time decay
dte2mindelta = [0,0,0,0,0,0,0,0,0,0,
                    0,0,0,0,0,0,0,0,0,0,
                    5,5,5,5,5,5,5,5,5,5,
                    10,10,10,10,10,10,10,10,10,10,
                    15,15,15,15,15,15,15,15,15,15]

# Positions of a single trading user
class myposition:

    def __init__(self, login, portfolio, session):
        self.pos = {}
        self.session = session
        self.login = login
        self.portfolio = portfolio
        df = qrytrades(session)
        print("Got",len(df),"trades")
        #df = db.findbyaltkey({"login":login,"portfolio":portfolio})
        for index, row in df.iterrows():
            p = self.findOrCreate(row["position"])
            p.load(row['legsym'], row['buysell'], row['px'], float(row['qty']), row['tradedt'], row['upx'], row['uvol'])
        print("Got",len(self.pos),"positions")
        for k,v in self.pos.items(): v.spot(False)

    def help(self):
        print("spot() - Read live prices")
        print("findOrCreate(key) - Creates new position")
        print("book(usesym, px, qty)")
        print("trades(key=None) - trades of stgy or all trades")
        print("###################### PNL ##########")
        print("pnl(key=None) - PNL summary of all positions closed and open")
        print("pnlbylegs(key=None,oc=O) - PNL by leg for each position")
        print("plot(key, type=pnl, incr=1, asofs=None) - plots metric (pnl|delta) for range of upx asof a given dates (ymds)")
        print("cone(key, ss) - How this stock aged since position was initiated against 1-sd benchmark")
        print("book('OC','AAPL-150-20230525',-2.3,10)")
        print("###################### Greeks/Risk ##########")
        print("alert() - Original Alert based on fdelta ")
        print("offsets('gamma') - Offset for any greek in a position ")
        print("manage() - Delta summary on call and put sides of each position ")
        print("greek('theta') - Greek by each open leg in a position ")
        print("     NetTheta = Credit received (-) or debit paid per day (+)")
        print("     NetDelta = Total long (+) or short (-) Deltas")
        print("     NetGamma = Deltas gained (+) or lost for unit change in upx (-)")

    def cone(self, key, ss):
        p = self(key)
        return p.cone(ss)

    def plot(self, key, type='pnl', incr=1, asofs=None):
        p = self(key)
        return p.plot(type,asofs,incr)

    def book(self, type, sym, px, qty, trddt=reldate(0)):
        ''' book("OC","AAPL-150-20230525",-2.3,10) '''
        arr = sym.split("-")
        print(str(arr))

        ###### Autofind or create the right key  #########
        for idx in range(25):
            key = arr[0].lower() + str(idx)
            if key not in self.pos.keys(): break
            if self.pos[key].isclosed() == False: break
        p = self.findOrCreate(key)

        if type[0] == 'O':
            leg = "B1" if float(px) > 0 else "S1"
        else:
            leg = "S1" if float(px) > 0 else "B1"

        ###### Get right upx,vol from trddt #########
        sdf = qryspot(arr[0])
        upx = float(sdf.spotprice.max())
        vol = float(sdf.spotvol.max())/100.0
        if int(trddt) < reldate(0):
            df = readohlc(arr[0],trddt).head(1)
            if len(df) > 0: upx = df.close.max()
        prm = oparam(upx,vol,trddt)
        p.bulktrade(arr[0], leg + type[1], int(arr[2]), arr[1], str(px), int(qty), prm)
        p.spot(True)

    def trades(self, key = None):
        df = qrytrades(self.session).sort_values(['tradedt','trades_id'],ascending=True)
        if key is not None: df = df[df['position'].str.contains(key.lower())]
        df['trades_id'] = df['trades_id'].apply(str)
        df['tradedt'] = df['tradedt'].apply(str)
        df = df["trades_id tradedt buysell legsym px qty upx uvol".split()]
        return df

    def findOrCreate(self, position):
        if position not in self.pos:
            self.pos[position] = tposition(self.session, self.login, self.portfolio, position)
        return self.pos[position]

    def pnl(self, key=None):
        if key is not None:
            return self(key)()
        df = pd.DataFrame()
        for k,v in self.pos.items():
            res = v.dpnl()
            pnl = 100*(res[0]-res[2]+res[1])
            pnl -= 0.65*res[3]
            expiry = v.trades().expiry.max()
            asofdt = v.trades()['asof'].min()
            open = 'N' if v.isclosed() else 'Y'
            delta, gamma, theta = 0, 0, 0
            if v.isclosed() == False:
                delta = v.greekdf('delta').netgreek.max()
                theta = v.greekdf('theta').netgreek.max()
                gamma = v.greekdf('gamma').netgreek.max()
            d = { "key":k, "pnl":pnl, "cost":100*res[2], "margin":v.margin(),
                "expiry":expiry, "open": open, "dte": datediff(int(expiry)),
                "delta": round(delta,2), "theta":round(theta,2), "gamma": round(gamma,2),
                "age": abs(datediff(int(asofdt))) }
            df = append_row(df,d)
        return df if len(df) == 0 else df[['key', 'pnl', 'cost', 'margin', 'age', 'delta', 'theta', 'gamma', 'dte', 'open', 'expiry']].sort_values('dte')

    def pnlbylegs(self, pos = None, oc = None):
        ''' PNL per each leg '''
        df = pd.DataFrame()
        for k,v in self.pos.items():
            df1 = v.pnlbylegs()
            df1['pos'] = k
            df = pd.concat([df,df1])
        if pos is not None: df = df.query("pos == '" + pos + "'")
        if oc is not None:  df = df.query("oc == '" + oc + "'")
        return df["pos key type strike qty openpx spotpx cost value oc unrealized realized".split()]

    def spot(self):
        df = pd.DataFrame()
        for k,v in self.pos.items():
            if v.isclosed() == False:
                lvd = v.spot(True)
                df = append_row(df,{"key":k,"upx":lvd[0],"vol":lvd[1],"dt":lvd[2]})
        return df

    def alert(self):
        df = pd.DataFrame()
        for k,v in self.pos.items():
            if v.isclosed() == False:
                sugg = v.suggestic()
                for arow in v.alert():
                    d = {"key":k, "strike": arow[5], "otmness": arow[1],
                        "Suggested": sugg,  "vol": round(arow[3],2),
                        "dte": arow[4],  "pos": arow[2] }
                    df = append_row(df, d)
        return df

    def manage(self):
        ''' Manage indicators are created for call and put sides of strategy seperately '''
        # adjust rollup func
        def adjustrollup(dte,optgreek):
            absgreek = abs(optgreek)
            dte = min(45,int(np.abs(dte)))
            return 1 if absgreek <= dte2mindelta[dte] else 0

        # adjust delta func
        def adjustdelta(dte,leggreek,shortqty):
            dte = min(45,int(np.abs(dte)))
            if shortqty <= 0: return 0
            d = np.abs(leggreek)/shortqty
            return 1 if d >= dte2maxdelta[dte] else 0

        df = self.greek('delta')
        df1 = self.offsets('delta')
        if len(df) == 0: return df

        df['shortqty'] = np.abs(np.minimum(df.qty,0))
        df['longqty'] = np.abs(df.qty) - np.abs(np.minimum(df.qty,0))
        df['optdelta'] = np.abs(df.optdelta)
        d = {'upx':'mean','vol':'mean','dte':'mean','optdelta':'max',
            'shortqty':'sum', 'longqty':'sum','legdelta':'sum','netdelta':'max'}
        df = df.groupby(['pos','callput']).agg(d).reset_index()

        df['rollup'] = df.apply(lambda row: adjustrollup(row['dte'],row['optdelta']),axis=1)
        df['adjustdelta'] = df.apply(lambda row: adjustdelta(row['dte'],row['legdelta'],row['shortqty']),axis=1)
        df = pd.merge(df,df1,on='pos',how='inner')
        print(guide)
        cols = "pos,callput,upx,vol,dte,optdelta,shortqty,longqty,pdelta,cdelta,cpdelta,rollup,adjustdelta,pdeltaoff,cdeltaoff,cpdeltaoff"
        return df if len(df) == 0 else df[cols.split(",")]

    def offsets(self, gtype = 'delta'):
        ''' Greeks with net values, offsets across call/put and long/shorts '''
        df = pd.DataFrame()
        for k,v in self.pos.items():
            if v.isclosed() == False:
                df1 = v.greekdf(gtype).head(1)
                df1['pos'] = k
                df = pd.concat([df,df1])

        if len(df) > 0:
            df = df["pos clgreek csgreek plgreek psgreek".split()]
            df['cgreek'] = df.clgreek + df.csgreek
            df['pgreek'] = df.plgreek + df.psgreek
            df['cpgreek'] = df.pgreek + df.cgreek
            df['cgreekoff'] = np.minimum(np.abs(df.clgreek),np.abs(df.csgreek))
            df['pgreekoff'] = np.minimum(np.abs(df.plgreek),np.abs(df.psgreek))
            df['cpgreekoff'] = np.minimum(np.abs(df.plgreek+df.psgreek),np.abs(df.clgreek+df.csgreek))

        for col in df.columns:
            if 'greek' in col:
                df = df.rename(columns={col: col.replace('greek',gtype)})
        return df

    def greek(self, gtype):
        ''' Greeks per each open leg '''
        df = pd.DataFrame()
        for k,v in self.pos.items():
            if v.isclosed() == False:
                df1 = v.greekdf(gtype)
                df1['pos'] = k
                df = pd.concat([df,df1])
        if len(df) > 0:
            df = df["pos type key callput upx vol dte optgreek qty leggreek netgreek".split()]
        for col in df.columns:
            if 'greek' in col:
                df = df.rename(columns={col: col.replace('greek',gtype)})
        return df

    def keys(self, openonly=True):
        res = []
        for k,v in self.pos.items():
            if v.isclosed() == False or openonly == False:
                res.append(k)
        return res

    def __call__(self, key):
        res = None
        for k,v in self.pos.items():
            if key.lower() in k:
                if res is None and v.isclosed():
                    res = v
                if v.isclosed() == False: res = v
        return res

    def __getitem__(self, key):
        return self.pos[key]
