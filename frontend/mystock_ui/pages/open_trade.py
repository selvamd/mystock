"""Open Trade (legacy ``multitrade``): book a multi-leg opening trade via ``trades.book``."""
import dash_bootstrap_components as dbc
from dash import Input, Output, State, callback, html

from .. import data
from ..legacy.option import reldate
from .common import error_box

NAME = "Open Trade"


def layout(pc):
    return dbc.Form([
        dbc.Label("Stock Symbol"), dbc.Input(id="otrade-sym", className="mb-2"),
        dbc.Label("Trade/Expiry Dates"), dbc.Input(id="otrade-dts", value=str(reldate(0)) + "/" + str(reldate(45)), className="mb-2"),
        dbc.Label("Leg Data"), dbc.Input(id="otrade-pxs", placeholder="B115P@2.39,S120P@3.20", className="mb-2"),
        dbc.Label("Contracts traded"), dbc.Input(id="otrade-qty", className="mb-2"),
        dbc.Button("Submit", id="otrade-submit", color="primary", n_clicks=0),
        html.Div(id="otrade-out", className="mt-2"),
    ])


def submit(session, sym, dts, pxs, qty):
    """Legacy multitrade submit body. Returns a status component."""
    try:
        por = data.get_portfolio(session["login"], session["token"])
        sym = (sym or "").strip()
        trddt, exprdt = (dts or "").split("/")
        for inpx in (pxs or "").split(","):
            stk, px = inpx.split("@")
            osym = sym + '-' + stk[1:-1] + '-' + exprdt
            if stk[0] == 'S': px = '-' + px
            por.book('O' + stk[-1], osym, px, qty, trddt)
    except Exception as e:  # noqa: BLE001
        return error_box(e)
    return dbc.Alert('Trade Submitted', color="success")


@callback(Output("otrade-out", "children"), Input("otrade-submit", "n_clicks"),
          State("session-store", "data"), State("otrade-sym", "value"), State("otrade-dts", "value"),
          State("otrade-pxs", "value"), State("otrade-qty", "value"), prevent_initial_call=True)
def _submit(n, session, sym, dts, pxs, qty):
    if not n or not session:
        return html.Div()
    return submit(session, sym, dts, pxs, qty)
