"""Smart Screens (legacy ``smartscreens``): ``rows | sort | cols``."""
import dash_bootstrap_components as dbc
from dash import Input, Output, callback, dcc, html

from .. import data
from ..legacy.myutils import featuretype
from .common import check_expr, found, guard, table, text_input

NAME = "Smart Screens"
QRYTXT = "mktcap == 0 | weekpnl | lastpx,weekpnl,mompnl,xadv,qdrop,qdropavg,rangescore,mktcap "


def layout(pc):
    return html.Div([
        text_input("smart-qry", "Smart Screen", value=QRYTXT),
        dbc.Switch(id="smart-help", label="Help", value=False, persistence=True, persistence_type="session"),
        html.Div(id="smart-doc"),
        html.Div(id="smart-out"),
    ])


def help_doc(on):
    if not on:
        return html.Div()
    items = []
    for k in featuretype.keys():
        items += [k.upper() + "=" + featuretype[k], html.Br()]
    return html.Div(items, className="small mb-2")


@guard
def render(qry):
    qry = qry or ""
    arr = qry.split("|")
    if len(arr) < 2:
        return html.Div()
    for a in arr:
        check_expr(a)
    col = arr[2].strip() if len(arr) == 3 else None
    df = data.get_screener().slice(arr[0].strip(), col, arr[1].strip())
    out = [found(df)]
    df = df.copy().head(100).set_index('ticker')
    out.append(table(df))
    return html.Div(out)


@callback(Output("smart-doc", "children"), Input("smart-help", "value"))
def _doc(on):
    return help_doc(on)


@callback(Output("smart-out", "children"), Input("smart-qry", "value"))
def _cb(qry):
    return render(qry)
