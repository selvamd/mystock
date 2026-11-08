"""Shadow (legacy ``shadow``): reversion iron-condor backtest via ``backtest.shadow``."""
import datetime as dt

from dash import Input, Output, callback, dcc, html

from ..legacy.option import reldate
from ..legacy.svcreader import qryshadow
from .common import guard, stock_input, table, text_input
import dash_bootstrap_components as dbc

NAME = "Shadow"


def layout(pc):
    ymd = reldate(-45)
    m45dt = dt.date(ymd // 10000, ymd // 100 % 100, ymd % 100)
    return html.Div([
        stock_input("shadow", pc.get("stock")),
        text_input("shadow-vols", "Volatility", placeholder="optvol,riskvol as decimals e.g. 0.35,0.45 (empty = estimate)"),
        dbc.Row([dbc.Label("Initiate date", width=3), dbc.Col(dcc.DatePickerSingle(
            id="shadow-date", min_date_allowed=m45dt, max_date_allowed=dt.date.today(), date=m45dt,
            display_format="MMM DD, YYYY"), width=9)], className="mb-2"),
        html.Div(id="shadow-out"),
    ])


@guard
def render(stk, vols, dtval):
    stk = (stk or "").upper()
    if not stk or not dtval:
        return html.Div()
    vols = (vols or "").upper().split(",")
    df = qryshadow(stk, str(dtval)[:10], vols[0], vols[-1])
    return table(df)


@callback(Output("shadow-out", "children"), Input({"type": "stock-input", "page": "shadow"}, "value"),
          Input("shadow-vols", "value"), Input("shadow-date", "date"))
def _cb(stk, vols, dtval):
    return render(stk, vols, dtval)
