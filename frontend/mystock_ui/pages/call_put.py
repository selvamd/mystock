"""Call/Put Data (legacy ``option_metrics``): option surfaces from ``options.chain``."""
from dash import Input, Output, callback, html

from ..legacy.utils import option_surface, options_history
from .common import guard, note, select, stock_input, table, urls

NAME = "Call/Put Data"
MTYPES = ['implied', 'delta', 'price', 'openint', 'vega', 'gamma', 'theta']


def layout(pc):
    return html.Div([
        stock_input("option_metrics", pc.get("stock")),
        select("cp-mtype", "Price or Delta", MTYPES),
        html.Div(id="cp-out"),
    ])


@guard
def render(stk, mtypeval):
    stk = (stk or "").upper()
    if len(stk) == 0 or mtypeval not in MTYPES:
        return html.Div()
    head = urls(stk)
    df = options_history(stk)
    if len(df) == 0:
        return html.Div([head, note("No option data")])
    cdf = option_surface(df, 'call', mtypeval).query("strike % 10 == 0")
    pdf = option_surface(df, 'put', mtypeval).query("strike % 10 == 0")
    return html.Div([head, note("Puts"), table(pdf), note("Calls"), table(cdf)])


@callback(Output("cp-out", "children"), Input({"type": "stock-input", "page": "option_metrics"}, "value"),
          Input("cp-mtype", "value"))
def _cb(stk, m):
    return render(stk, m)
