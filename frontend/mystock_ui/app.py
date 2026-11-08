"""Dash application shell: sidebar navigation, login gating and shared stores.

``server`` is the Flask app for gunicorn (``gunicorn mystock_ui.app:server``).
"""
from __future__ import annotations

import logging
import os
import secrets

import dash
import dash_bootstrap_components as dbc
from dash import ALL, Input, Output, State, callback, dcc, html, no_update

from . import config
from .pages import BY_PATH, PAGES
from .pages import login as login_page
from .pages.common import error_box

logging.basicConfig(level=os.environ.get("MYSTOCK_LOG_LEVEL", "INFO"),
                    format="%(asctime)s %(levelname)s %(name)s: %(message)s")

app = dash.Dash(
    __name__,
    title="Options Castle",
    external_stylesheets=[dbc.themes.BOOTSTRAP],
    suppress_callback_exceptions=True,
)
server = app.server
server.secret_key = config.UI_SECRET or secrets.token_hex(32)

app.layout = dbc.Container(
    [
        dcc.Location(id="url"),
        dcc.Store(id="session-store", storage_type="session"),
        dcc.Store(id="stock-store", storage_type="session", data="SPY"),
        html.H2("Options Castle", className="my-3"),
        dbc.Row(
            [
                dbc.Col(html.Div(id="sidebar"), md=2, className="border-end"),
                dbc.Col(dcc.Loading(html.Div(id="page-content"), type="default", delay_show=400), md=10),
            ]
        ),
    ],
    fluid=True,
)


def logged_in(session) -> bool:
    return bool(session and session.get("token"))


def sidebar(pathname, session):
    pages = PAGES if logged_in(session) else PAGES[:1]
    active = pathname if pathname in BY_PATH else "/login"
    return html.Div([
        html.Small("Choose a Page", className="text-muted"),
        dbc.Nav([dbc.NavLink(name, href=path, active=(path == active)) for name, path, _ in pages],
                vertical=True, pills=True),
    ])


def render_page(pathname, session, stock):
    """Legacy: only Login is reachable until authenticated."""
    if not logged_in(session):
        return login_page.layout({})
    mod = BY_PATH.get(pathname, login_page)
    pc = {"token": session["token"], "login": session.get("login", ""), "stock": stock or "SPY"}
    try:
        return mod.layout(pc)
    except Exception as e:  # noqa: BLE001 - pages already guard; last-resort safety net
        logging.getLogger(__name__).exception("layout of %s failed", mod.NAME)
        return error_box(e)


@callback(Output("sidebar", "children"), Input("url", "pathname"), Input("session-store", "data"))
def _sidebar(pathname, session):
    return sidebar(pathname, session)


@callback(Output("page-content", "children"), Input("url", "pathname"), Input("session-store", "data"),
          State("stock-store", "data"))
def _page(pathname, session, stock):
    return render_page(pathname, session, stock)


@callback(Output("stock-store", "data"), Input({"type": "stock-input", "page": ALL}, "value"),
          State("stock-store", "data"), prevent_initial_call=True)
def _stock(values, current):
    """Shared current stock (legacy session('global','stock'))."""
    for v in values or []:
        if v and v.strip() and v.strip().upper() != (current or ""):
            return v.strip().upper()
    return no_update


def main():  # pragma: no cover - dev entry point
    app.run(host=os.environ.get("MYSTOCK_UI_HOST", "127.0.0.1"), port=config.UI_PORT,
            debug=os.environ.get("MYSTOCK_UI_DEBUG", "") == "1")
