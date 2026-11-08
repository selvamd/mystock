"""Trend (legacy ``mkt_summ_trend``): winners/losers and turnaround trends."""
from dash import Input, Output, callback, html

from .common import guard, note, ss_call, table, text_input

NAME = "Trend"


def layout(pc):
    return html.Div([
        text_input("trend-qry", "Query", value="mktcap > 0", placeholder="mktcap > 10 and sect == 'TECH' and ..."),
        html.Div(id="trend-out"),
    ])


@guard
def render(qry):
    if not qry:
        return html.Div()
    return html.Div([
        note("Winners and Losers"), table(ss_call("mktsummary", qry, "perf")),
        note("Turnaround Trends"), table(ss_call("mktsummary", qry, "trend")),
    ])


@callback(Output("trend-out", "children"), Input("trend-qry", "value"))
def _cb(qry):
    return render(qry)
