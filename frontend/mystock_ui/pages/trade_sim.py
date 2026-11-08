"""Trade Sim (legacy ``trade_sim``): what-if plots for typed leg trades."""
from dash import Input, Output, State, callback, dcc, html, no_update

from ..legacy.option import reldate
from ..legacy.positions import tposition
from .common import error_box, graph, guard, note, select, slider, stock_input, text_input, upxvol, pending

NAME = "Trade Sim"
MTYPES = ['price', 'pnl', 'delta', 'gamma', 'theta']


def layout(pc):
    return html.Div([
        stock_input("trade_sim", pc.get("stock")),
        text_input("tsim-trades", "Booked  Trades", placeholder="B[30D]100P10@5.20,.."),
        text_input("tsim-open", "Overlay Trades", placeholder="B[30D]100P10@0.."),
        select("tsim-mtype", "Option Metric", MTYPES),
        html.Div(id="tsim-msg"),
        slider("tsim-last", "Set Market Price", 0.0, 1000.0, 0.0, step=0.01),
        slider("tsim-vol", "Set Market Volatility", 0.0, 150.0, 0.0, step=0.01),
        slider("tsim-dte", "Set Days to Expire", 0, 400, 45, step=1),
        slider("tsim-incr", "Increment", 0, 25, 5, step=1),
        dcc.Store(id="tsim-ready"),
        html.Div(id="tsim-out"),
    ])


@callback(Output("tsim-last", "max"), Output("tsim-last", "value"), Output("tsim-vol", "value"),
          Output("tsim-ready", "data"), Output("tsim-msg", "children"),
          Input({"type": "stock-input", "page": "trade_sim"}, "value"))
def _sliders(stk):
    stk = (stk or "").upper()
    if not stk:
        return no_update, no_update, no_update, None, ""
    try:
        last, ivol = upxvol(stk)
    except Exception as e:  # noqa: BLE001
        return no_update, no_update, no_update, None, error_box(e)
    return 2 * float(last), float(last), float(ivol), stk, ""


@guard
def render(stk, trades, opentrades, mtypeval, last, ivol, dte, incr):
    stk = (stk or "")
    trades, opentrades = trades or "", opentrades or ""
    if not (len(stk) > 0 and len(trades) + len(opentrades) > 0) or mtypeval not in MTYPES:
        return html.Div()
    last, ivol = float(last or 0.0), float(ivol or 0.0)
    dte, incr = int(dte if dte is not None else 45), int(incr if incr is not None else 5)
    t = tposition('x', 'x', 'x', 'x')
    t.ticker, t.exprdt = stk, reldate(dte)
    t.set(last, ivol / 100, reldate(0))
    out = []
    if len(trades) > 0: t.simtrades(trades)
    if len(opentrades) > 0:
        arr = pending(opentrades, last, ivol / 100.0, dte)
        out.append(note("Pending leg trades: " + " ".join(arr)))
        t.simtrades(",".join(arr))
    out.append(graph(t.plot(mtypeval, None, incr)))
    return html.Div(out)


@callback(Output("tsim-out", "children"), Input("tsim-ready", "data"), Input("tsim-trades", "value"),
          Input("tsim-open", "value"), Input("tsim-mtype", "value"), Input("tsim-last", "value"),
          Input("tsim-vol", "value"), Input("tsim-dte", "value"), Input("tsim-incr", "value"))
def _cb(stk, trades, opentrades, m, last, ivol, dte, incr):
    if not stk:
        return html.Div()
    return render(stk, trades, opentrades, m, last, ivol, dte, incr)
