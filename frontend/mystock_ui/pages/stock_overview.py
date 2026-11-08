"""Stock Overview (legacy ``extract``): tabs Price Chart / Technicals / Options / Fundamentals / News.

Each tab is rendered only when selected, so an UPSTREAM failure (options/fundamentals/news
come from third parties server-side) only affects its own tab.
"""
import dash_bootstrap_components as dbc
from dash import ALL, Input, Output, callback, dcc, html

from .. import data
from .. import api
from ..legacy.utils import finstmt, options_summary
from .common import graph, guard, note, ss_call, stock_input, table, urls

NAME = "Stock Overview"
TABS = "Price Chart,Technicals,Options,Fundamentals,News".split(',')


def layout(pc):
    return html.Div([
        stock_input("overview", pc.get("stock"), label="Stock Symbol"),
        html.Div(id="overview-urls"),
        dbc.Tabs([dbc.Tab(label=t, tab_id=t) for t in TABS], id="overview-tabs", active_tab=TABS[0],
                 persistence=True, persistence_type="session"),
        html.Div(id="overview-out", className="mt-2"),
    ])


@guard
def render_urls(sym):
    sym = (sym or "").upper()
    return urls(sym) if sym else html.Div()


@guard
def render_tab(sym, tab):
    sym = (sym or "").upper()
    if not sym:
        return html.Div()
    if tab == "Price Chart":
        return graph(ss_call("tsstock", sym, 365))
    if tab == "Technicals":
        df = data.get_screener()[sym + ".summary"]["name value".split()].copy().set_index('name')
        return table(df)
    if tab == "Options":
        df = options_summary(sym)
        if len(df) > 0:
            df = df.copy().set_index('expr')
            return html.Div([note("Options Summary"), table(df)])
        return note("No option data")
    if tab == "Fundamentals":
        df = finstmt(sym)
        if len(df) > 0:
            return html.Div([note("Financial Statement"), table(df)])
        return note("No financial statements")
    if tab == "News":
        df = api.news_list(sym, 10)
        items = []
        for _, js in df.iterrows():
            pub = str(js.get('published'))[:10]
            items.append(html.Div([
                html.A("" + str(js.get('author')) + " on " + pub, href=js.get('url'), target="_blank", rel="noopener"),
                html.P(str(js.get('sentiment_reasoning') or "")),
            ]))
        return html.Div(items or [note("No news")])
    return html.Div()


@callback(Output("overview-urls", "children"), Input({"type": "stock-input", "page": "overview"}, "value"))
def _urls(sym):
    return render_urls(sym)


@callback(Output("overview-out", "children"), Input({"type": "stock-input", "page": "overview"}, "value"),
          Input("overview-tabs", "active_tab"))
def _tab(sym, tab):
    return render_tab(sym, tab)
