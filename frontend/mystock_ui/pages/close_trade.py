"""Close Trade (legacy ``closetrade``)."""
import dash_bootstrap_components as dbc
from dash import Input, Output, State, callback, dcc, html, no_update

from .. import data
from ..legacy.option import reldate
from .common import error_box, guard, note

NAME = "Close Trade"


def open_legs(por):
    if len(por.pos) == 0:
        return None
    return por.pnlbylegs().query("qty != 0")


@guard
def layout(pc):
    por = data.get_portfolio(pc["login"], pc["token"])
    df = open_legs(por)
    if df is None or len(df) == 0:
        return note('No trades to close')
    keys = list(df.key.values)
    return html.Div([
        dbc.Label("Leg to close"),
        dcc.Dropdown(id="ctrade-key", options=keys, value=keys[0], clearable=False, className="mb-2"),
        dbc.Label("Trade Date"), dbc.Input(id="ctrade-dt", value=str(reldate(0)), className="mb-2"),
        dbc.Label("Option Price"), dbc.Input(id="ctrade-px", className="mb-2"),
        dbc.Label("Contracts traded"), dbc.Input(id="ctrade-qty", className="mb-2"),
        dbc.Button("Close", id="ctrade-submit", color="primary", n_clicks=0),
        html.Div(id="ctrade-out", className="mt-2"),
    ])


def defaults(session, key):
    por = data.get_portfolio(session["login"], session["token"])
    df = open_legs(por)
    df1 = df[df.key == key]
    return str(round(df1.spotpx.max(), 2)), str(abs(int(df1.qty.max())))


@callback(Output("ctrade-px", "value"), Output("ctrade-qty", "value"), Input("ctrade-key", "value"),
          State("session-store", "data"))
def _defaults(key, session):
    if not key or not session:
        return no_update, no_update
    try:
        return defaults(session, key)
    except Exception:  # noqa: BLE001
        return no_update, no_update


def submit(session, key, dt, px, qty):
    try:
        por = data.get_portfolio(session["login"], session["token"])
        arr = key.split('@')
        px = str(px)
        if key[-3] == 'B': px = '-' + px
        # LEGACY: book() picks the first open position of the ticker for the closing leg.
        por.book('C' + key[-1], arr[0] + '-' + arr[1] + '-20' + arr[2], px, qty, dt)
    except Exception as e:  # noqa: BLE001
        return error_box(e)
    return dbc.Alert('Trade Submitted', color="success")


@callback(Output("ctrade-out", "children"), Input("ctrade-submit", "n_clicks"), State("session-store", "data"),
          State("ctrade-key", "value"), State("ctrade-dt", "value"), State("ctrade-px", "value"),
          State("ctrade-qty", "value"), prevent_initial_call=True)
def _submit(n, session, key, dt, px, qty):
    if not n or not session or not key:
        return html.Div()
    return submit(session, key, dt, px, qty)
