import sys
from .option import *
from .positions import *
from .myutils import *
from .plots import *
# CHANGED: webbrowser/sqlite3 imports removed (unused; no local DB access)
import os   # CHANGED: for MYSTOCK_SHELL_LOGIN/PASSWORD
import atexit
import time
from sys import platform

from . import utils
from . import svcreader

def teardown_my_shell():
    print("Called at exit")
    if 'session' in globals() and session: logout(session)   # CHANGED: only when logged in
    # CHANGED: no prediction.csv write - globalcontext.predict() now persists via predictions.add
    # things I want to happen when iPython exits

def funcs(var = dir()):
    #print([f for f in var if not f.startswith('_')])
    fns = []
    clz = []
    for f in var:
        if str(getattr(sys.modules[__name__], f)).startswith("<class"):
            clz.append(f)
        if str(getattr(sys.modules[__name__], f)).startswith("<function"):
            fns.append(f)
    print("***** functions *******")
    print(fns)
    print("*****  classes  *******")
    print(clz)

#print("#describe(df1.rangescore)\n#buys(df,-5.0)\n#watch(df,'bank')\n#group_perf(df,'sect')\n#df1[tblcols()].sort_values('yoypnl',ascending=False)")
#summary(ctx.df)
#print(ss.quote("SPY,QQQ,IWM").sort_values('lastpx',ascending=False))
#print(ss.quote(Stocks.ETF.all()).sort_values('weekpnl'))
#print("\n\n\t\t\t\t\t\t\t\t\t\t\tCustom Portfolio \n")
#print(ctx.portfolio('MW').sort_values('mompnl'))
atexit.register(teardown_my_shell)
ctx = globalcontext()
ss = stock_screener(ctx)
mleg.screener = ss

## ipython fillers for ssquery and ssqueryhelp
q = Symbol(ctx)

feat = autofill()

for f in ss.df.columns:
    feat.__create__(f,f)

funcs = {
    'generic': 'movers,topn,query,quote,squery,watch,distribution,tsmetrics,yymetric,price_action',
    'market':  'mktsummary,tsmktvalue,tsmktshare',
    'segment': 'groupsummary,industry,heat,mktsize,portfolio,sector,summary',
    'stock': 'desc,like,mktpx,mktshifts,tsstock,stk,dr,drfreq,spotprice,spotvol',
    'screen': 'blazers,buys,corr,dips,etfs,laggards,leaders,pe,rebounds,soldoffs,steady,indleader',
    'technical': 'bband,macd,mvavg,scatter,tsgrowth,drscatter,ffe',
    'web': 'dataroma,chartmill,fidelity,finviz,morningstar,tipranks,volafy,yhoo,yhopt,zack',
    'volatility': 'lowtailrisk,volchg,volchgopt,tailriskdata,cone,tailrisk,tsvolgrowth',
    'option': 'calls,dcalls,dputs,fdeltas,opricer,puts,strikes'
}

ssf = autofill()
for k, v in funcs.items():
    f = autofill()
    for s in v.split(","):
        f.__create__(s,s)
    ssf.__create__(k,f)

ssq = autofill()
for s in [ss,ss.zmkt,ss.zscr,ss.zsym,ss.zweb,ss.zviz]:
    for f in code(s):
        ssq.__create__(f,f)

senti = {
    "Economy Outlook":"Bear", "MktTrend": "Neutral", "Energy (USO,XLE,XOP)":"Weak",
    "Healthcare (XLV,VRTX,REGN)": "Neutral", "Retail":"Neutral", "Finance":"Weak",
    "Tech(QQQ,MSFT,XLK)":"Strong", "China (FXI,EEM,KWEB,BABA)": "Strong",
    "Metals(GLD,GDX,SLV)":"Peaked", "Food (XLP)":"Strong"
}

sdf = pd.DataFrame()
for k,v in senti.items():
    sdf = append_row(sdf,{"key":k,"val":v})

print(sdf)
print("-----")

########################## PORTFOLIO #####################################
def portfolio(name,sess):
    return myposition(name,'p0',sess)

# CHANGED: legacy auto-logged in with hard-coded credentials on macOS; now opt-in through
# MYSTOCK_SHELL_LOGIN / MYSTOCK_SHELL_PASSWORD.
session = ''
if os.environ.get('MYSTOCK_SHELL_LOGIN'):
    session = str(login(os.environ['MYSTOCK_SHELL_LOGIN'], os.environ.get('MYSTOCK_SHELL_PASSWORD', '')))
    sel = portfolio(os.environ['MYSTOCK_SHELL_LOGIN'],session)   # CHANGED: login from env (was 'Sel')
    print("Logged in with session " + session)
########################## PORTFOLIO #####################################
