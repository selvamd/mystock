"""Movers (legacy ``movers``)."""
from dash import Input, Output, callback, html

from .common import found, guard, select, slider, ss_call, table

NAME = "Movers"
METRICS = ['daypnl', 'weekpnl', 'mompnl', 'qoqpnl', 'yoypnl', 'pnldir',
           'lastdevz', 'volscore', 'weekdip', 'momdip']
SECTS = "All,Reit,Manufacturing,Finance,Health,Energy,Trade,Tech".split(",")


def layout(pc):
    return html.Div([
        select("movers-metric", "Metrics", METRICS),
        slider("movers-thresh", "Limits", -100.0, 100.0, -5.0, step=0.5),
        slider("movers-mktcap", "Mktcap", 0.0, 100.0, 20.0, step=0.5),
        select("movers-sector", "Sector", SECTS),
        html.Div(id="movers-out"),
    ])


def limits(metric):
    return (-100.0, 100.0) if 'pnl' in (metric or "") else (-10.0, 10.0)


@callback(Output("movers-thresh", "min"), Output("movers-thresh", "max"), Input("movers-metric", "value"))
def _limits(metric):
    return limits(metric)


@guard
def render(metric, thresh, mktcap, sect):
    if metric not in METRICS:
        return html.Div()
    minv, maxv = limits(metric)
    thresh = min(max(float(thresh if thresh is not None else -5.0), minv), maxv)
    sectval = None if sect in (None, 'All') else sect.upper()
    df = ss_call("movers", float(thresh), metric, float(mktcap if mktcap is not None else 20.0), sectval)
    out = [found(df)]
    df = df.copy().set_index("ticker")
    out.append(table(df))
    return html.Div(out)


@callback(Output("movers-out", "children"), Input("movers-metric", "value"), Input("movers-thresh", "value"),
          Input("movers-mktcap", "value"), Input("movers-sector", "value"))
def _cb(metric, thresh, mktcap, sect):
    return render(metric, thresh, mktcap, sect)
