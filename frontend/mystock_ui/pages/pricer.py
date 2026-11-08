"""Pricer (legacy ``pricer``): strategy templates priced with the legacy mleg/Stgy code."""
import dash_bootstrap_components as dbc
from dash import Input, Output, State, callback, dcc, html, no_update

from ..legacy.option import Stgy, ovar
from .common import graph, guard, guard_multi, note, select, slider, ss_call, stock_input, table, text_input, upxvol, urls, error_box

NAME = "Pricer"
SENTIMENTS = ['Neutral', 'Bullish', 'Bearish', 'Volatile']
DIRECTIONAL = ['credit', 'debit', 'put', 'call', 'collar', 'stock', 'bwb', 'zebra']
NONDIR = ['condor', 'ironfly', 'butterfly', 'strangle', 'straddle', 'zeehbs']


def strategies(senti):
    return DIRECTIONAL if senti in ('Bullish', 'Bearish') else NONDIR


def stype_of(senti, stg):
    if senti == 'Bullish' or senti == 'Bearish':
        return "bull_" if senti == 'Bullish' else 'bear_'
    return "vol_" if senti == 'Volatile' or stg == 'zeehbs' else 'peg_'


def layout(pc):
    return html.Div([
        stock_input("pricer", pc.get("stock")),
        select("pricer-senti", "Sentiment", SENTIMENTS),
        select("pricer-stgy", "Strategy", NONDIR),
        html.Div(id="pricer-msg"),
        slider("pricer-last", "Set Market Price", 0.0, 1000.0, 0.0, step=0.01),
        slider("pricer-vol", "Set Market Volatility", 0.0, 150.0, 0.0, step=0.01),
        slider("pricer-dte", "Set Days to Expire", 0, 365, 45, step=1),
        text_input("pricer-deltas", "Leg Delta Settings", value="", persist=True),
        dbc.Checkbox(id="pricer-lock", label="Lock Strikes", value=False, className="mb-2"),
        dcc.Store(id="pricer-ready"),
        html.Div(id="pricer-out"),
    ])


@callback(Output("pricer-stgy", "options"), Output("pricer-stgy", "value"),
          Input("pricer-senti", "value"), State("pricer-stgy", "value"))
def _stgy(senti, current):
    opts = strategies(senti)
    return [{"label": o, "value": o} for o in opts], current if current in opts else opts[0]


def slider_defaults(stk):
    last, ivol = upxvol(stk)
    maxlast = 1000.0 if last == 0.0 else 2.0 * last
    return float(last), float(maxlast), float(ivol)


@callback(Output("pricer-last", "max"), Output("pricer-last", "value"), Output("pricer-vol", "value"),
          Output("pricer-ready", "data"), Output("pricer-msg", "children"),
          Input({"type": "stock-input", "page": "pricer"}, "value"))
def _sliders(stk):
    stk = (stk or "").upper()
    if not stk:
        return no_update, no_update, no_update, None, ""
    try:
        last, maxlast, ivol = slider_defaults(stk)
    except Exception as e:  # noqa: BLE001
        return no_update, no_update, no_update, None, error_box(e)
    return maxlast, last, ivol, stk, ""


@guard_multi(3)
def render(stk, senti, stg, last, ivol, dte, deltas, lock):
    """Returns (children, deltas_value, deltas_placeholder)."""
    stk = (stk or "").upper()
    if senti not in SENTIMENTS or stg not in strategies(senti):
        return html.Div(), no_update, no_update
    stype = stype_of(senti, stg)
    val = Stgy.init(stype + stg)
    placeholder = val + '/5'
    deltas = deltas or ''
    if deltas == '': deltas = val
    if len(stk) == 0 or not hasattr(Stgy, stype + stg):
        return html.Div(), no_update, placeholder
    last, ivol, dte = float(last or 0.0), float(ivol or 0.0), int(dte if dte is not None else 45)
    o = ss_call("opricer", stk, getattr(Stgy, stype + stg), deltas, ovar(last, ivol / 100.0, int(dte)))
    o.set(last, ivol / 100.0)
    df = o.legdf()
    new_deltas = no_update
    if lock:
        s = df.sort_values('strike').strike.unique()
        new_deltas = ",".join(map(lambda x: str(x), s))
    dbcr = float(df.netprice.max())
    df1 = o.dte2opx(dbcr).set_index('dte')
    return html.Div([
        urls(stk),
        note("Suggested strategy"), table(df),
        note("Sensitivity as MaxProfit%"), table(df1),
        note("Price Plot"), graph(o.plot()),
    ]), new_deltas, placeholder


@callback(Output("pricer-out", "children"), Output("pricer-deltas", "value"), Output("pricer-deltas", "placeholder"),
          Input("pricer-ready", "data"), Input("pricer-senti", "value"), Input("pricer-stgy", "value"),
          Input("pricer-last", "value"), Input("pricer-vol", "value"), Input("pricer-dte", "value"),
          Input("pricer-deltas", "value"), Input("pricer-lock", "value"))
def _cb(stk, senti, stg, last, ivol, dte, deltas, lock):
    if not stk:
        return html.Div(), no_update, no_update
    return render(stk, senti, stg, last, ivol, dte, deltas, lock)
