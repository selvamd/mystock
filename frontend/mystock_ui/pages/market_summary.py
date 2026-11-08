"""Market Summary (legacy ``summary``)."""
from dash import Input, Output, callback, html

from .common import graph, guard, select, ss_call, table, text_input

NAME = "Market Summary"
GROUPS = ["Sect", "Ind", "PE Type", "Cross", "Mkt Size"]
METRICS = ['pnldir', 'qdrop', 'daypnl', 'weekpnl', 'mompnl', 'qoqpnl', 'yoypnl',
           'cagr', 'corr', 'xadv', 'momtr', 'px2bbpct', 'volratio', 'rangescore', 'prscore',
           'trisktouch', 'triskbreach', 'volscore', 'histvol', 'optvol', 'optivol', 'weekdip',
           'lastdevz', 'rsi', 'momdip', 'bumpiness', 'r2']


def layout(pc):
    return html.Div([
        text_input("summary-qry", "Query", value="mktcap > 0", placeholder="mktcap > 0"),
        select("summary-group", "Group", GROUPS),
        select("summary-metric", "Metrics", METRICS),
        html.Div(id="summary-out"),
    ])


@guard
def render(qry, group, metric):
    if not metric or not group:
        return html.Div()
    group = group.lower().replace(' ', '')
    fig, df = ss_call('summary', group, metric, qry or "mktcap > 0")
    return html.Div([table(df), graph(fig)])


@callback(Output("summary-out", "children"), Input("summary-qry", "value"),
          Input("summary-group", "value"), Input("summary-metric", "value"))
def _cb(qry, group, metric):
    return render(qry, group, metric)
