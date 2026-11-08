"""Stock Time Charts (legacy ``timeseries``)."""
from dash import Input, Output, callback, html

from .common import graph, guard, select, slider, ss_call, stock_input, urls, bold

NAME = "Stock Time Charts"
TYPES = [
    'growth',     'Stocks 1 yr growth compared to its ETFs',
    'stock6m',    'Recent 180 days price moves',
    'pricelevels', 'Key Price Levels',
    'summary',    'Summary of critical metrics',
    'drawdown',   'Spot price drop% from peak price until that moment',
    'metrics',    'Timeseries on various metrics like pnldir',
    'cone',       'Comparing last 45 day price move to 1-to-2 stdev range',
    'pxdist',     'Distribution of 180 day prices vs std normal',
    'swingrange', 'Price range as percent of price for various time windows',
    'swing',      '45 day price range as percentage of price as timeseries',
    'bband',      'Current Price within Bollinger band',
    'stock',      'Long term  price chart from 2015',
    'macd',       'Historical Price compared to 30,60,200 day mvavgs',
    'rsi',        'Resistance and Support levels as timeseries',
    'volgrowth',  'Comparison of realized and implied vols',
    'scatter',    'Level of the stock relative to the market',
    'drscatter',  'Daily Return level of the stock relative to the market',
    'drtrend',    'Daily spread and change as percent of price as timeseries',
    'voltime',    'Realized annualized vol for various time windows'
]
CHARTS = TYPES[0:len(TYPES):2]
DESC = dict(zip(TYPES[0::2], TYPES[1::2]))
METRICS = ("DAYPNL,WEEKPNL,MOMPNL,MOMDIP,QOQPNL,YOYPNL,DTPM,STLPM,OPTVOL,OPTIVOL,CCI,CCIQ,RSI,"
           "RANGESCORE,LASTDEVZ,CORR,BUMPINESS,COSKEW,PCTCHG,ATR,ADV,TRMON,PNLDIR").split(",")


def layout(pc):
    return html.Div([
        select("ts-type", "Chart type", CHARTS),
        html.Div(id="ts-desc", className="mb-2"),
        stock_input("timeseries", pc.get("stock")),
        html.Div(select("ts-metrics", "Metrics", METRICS, value=["MOMDIP", "CCI", "PNLDIR"], multi=True), id="ts-metrics-row"),
        html.Div(slider("ts-months", "Set Months Elapsed", 0, 100, 12, step=1), id="ts-months-row"),
        html.Div(slider("ts-cone-months", "Set Months Elapsed", 0, 12, 2, step=1), id="ts-cone-row"),
        html.Div(id="ts-out"),
    ])


@callback(Output("ts-desc", "children"), Output("ts-metrics-row", "style"), Output("ts-months-row", "style"),
          Output("ts-cone-row", "style"), Input("ts-type", "value"))
def _vis(fx):
    hide, show = {"display": "none"}, {}
    return (bold(DESC.get(fx, "")), show if fx == 'metrics' else hide,
            show if fx in ['growth', 'drawdown', 'stock6m'] else hide, show if fx == 'cone' else hide)


def figure(fx, stk, metric, months, cone_months):
    if fx == 'metrics':
        return ss_call("ts" + fx, stk, ",".join(metric or []))
    if fx in ['growth', 'drawdown', 'stock6m']:
        dte = int(months if months is not None else 12) * 30
    elif fx == 'cone':
        dte = int(cone_months if cone_months is not None else 2) * 30
    if fx in ['growth', 'drawdown']:
        return ss_call("ts" + fx, stk, dte)
    if fx in ['stock', 'volgrowth', 'voltime']:
        return ss_call("ts" + fx, stk)
    if fx in ['stock6m']:
        return ss_call("tsstock", stk, dte)
    if fx in ['cone']:
        return ss_call("cone", stk, 0, 35, dte)
    if fx in ['swing']:
        arr = stk.split('/')
        days = 45 if len(arr) != 2 else int(arr[1])
        return ss_call("swing", arr[0], days)
    if fx in ['summary']:
        return ss_call("month_ohlc", stk)
    return ss_call(fx, stk)


@guard
def render(fx, stk, metric, months, cone_months):
    stk = (stk or "").upper()
    if fx not in CHARTS or len(stk) == 0:
        return html.Div()
    head = urls(stk.split("/")[0])  # legacy passed "AAPL/30" (swing) straight to urls() and failed
    return html.Div([head, graph(figure(fx, stk, metric, months, cone_months))])


@callback(Output("ts-out", "children"), Input("ts-type", "value"),
          Input({"type": "stock-input", "page": "timeseries"}, "value"), Input("ts-metrics", "value"),
          Input("ts-months", "value"), Input("ts-cone-months", "value"))
def _cb(fx, stk, metric, months, cone_months):
    return render(fx, stk, metric, months, cone_months)
