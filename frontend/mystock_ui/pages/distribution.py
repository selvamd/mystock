"""Distribution (legacy ``distribution``)."""
from dash import Input, Output, callback, html

from .common import check_expr, graph, guard, ss_call, table, text_input

NAME = "Distribution"


def layout(pc):
    return html.Div([
        text_input("dist-metric", "Metric/Expression", placeholder="qoqpnl or (optvol - histvol)"),
        text_input("dist-qry", "Query", placeholder="mktcap > 10 and sect == 'TECH' and ..."),
        html.Div(id="dist-out"),
    ])


@guard
def render(metric, qry):
    metric = metric or ""
    qry = qry or ""
    qry = qry[0:1].lower() + qry[1:]
    if len(metric) == 0 or len(qry) == 0:
        return html.Div()
    check_expr(metric)
    check_expr(qry)
    df = ss_call("desc", metric, qry)
    fig = ss_call("distribution", metric, qry)
    return html.Div([table(df), graph(fig)])


@callback(Output("dist-out", "children"), Input("dist-metric", "value"), Input("dist-qry", "value"))
def _cb(metric, qry):
    return render(metric, qry)
