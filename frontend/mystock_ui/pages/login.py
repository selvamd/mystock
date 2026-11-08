"""Login (legacy ``intro``): login form when logged out; welcome + VIX trend + Logout when logged in."""
import logging

import dash_bootstrap_components as dbc
import numpy as np
from dash import Input, Output, State, callback, html, no_update

from .. import data
from ..legacy import svcreader
from ..legacy.plots import graph as lgraph
from .common import error_box, graph, guard

NAME = "Login"
log = logging.getLogger(__name__)


def login_form():
    return dbc.Form(
        [
            dbc.Label("User login"), dbc.Input(id="login-user", type="text", className="mb-2"),
            dbc.Label("Password  "), dbc.Input(id="login-pass", type="password", className="mb-2"),
            dbc.Button("Submit", id="login-submit", color="primary", n_clicks=0),
            html.Div(id="login-msg", className="mt-2"),
        ]
    )


@guard
def welcome(pc):
    ctx = data.get_context()
    out = [html.P("Welcome " + pc["login"] + ". Happy Trading !!!")]
    vix = ctx.vix.tail(255)
    if vix[["VIX", "SKEW"]].isna().all().all():
        out.append(dbc.Alert("Market volatility data (VIX) is currently unavailable from the upstream provider.", color="warning"))
    else:
        out.append(graph(lgraph.line(vix, 'DATE', 'VIX,SKEW', "Market Volatility Trend - YOY")))
    return html.Div(out)


def layout(pc):
    if not pc.get("token"):
        return login_form()
    return html.Div([welcome(pc), dbc.Button("Logout", id="logout-btn", color="secondary", n_clicks=0)])


def do_login(user, passwd):
    """Returns (session_data, stock, message)."""
    try:
        token = svcreader.login(user or "", passwd or "")
    except Exception as e:  # noqa: BLE001
        return no_update, no_update, error_box(e)
    if not token:
        return no_update, no_update, dbc.Alert("Invalid login or password.", color="danger")
    return {"token": token, "login": user}, "SPY", ""


@callback(
    Output("session-store", "data", allow_duplicate=True),
    Output("stock-store", "data", allow_duplicate=True),
    Output("login-msg", "children"),
    Input("login-submit", "n_clicks"),
    State("login-user", "value"),
    State("login-pass", "value"),
    prevent_initial_call=True,
)
def _login(n, user, passwd):
    if not n:
        return no_update, no_update, no_update
    return do_login(user, passwd)


def do_logout(session):
    if session and session.get("token"):
        try:
            svcreader.logout(session["token"])
        except Exception:  # noqa: BLE001 - logging out locally is enough
            log.warning("auth.logout failed", exc_info=True)
    return None


@callback(
    Output("session-store", "data", allow_duplicate=True),
    Input("logout-btn", "n_clicks"),
    State("session-store", "data"),
    prevent_initial_call=True,
)
def _logout(n, session):
    if not n:
        return no_update
    return do_logout(session)
