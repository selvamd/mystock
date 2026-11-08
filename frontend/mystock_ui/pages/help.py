"""Help (legacy ``help``): feature dictionary with 5-number summaries."""
import dash_bootstrap_components as dbc
from dash import html

from .. import data
from ..legacy.myutils import featuredict, featuretype, featureval
from .common import guard

NAME = "Help"
TABS = "Facts Performance Price Risk Level Correlation Direction Volatility".split()
KEYS = "facts,mktperf,mktpx,mktrisk,mktlvl,mktcorr,mktdir,mktvol".split(",")


@guard
def layout(pc):
    ss = data.get_screener()
    tabs = []
    for idx in range(len(TABS)):
        items = [html.P(html.B("Summary contains 5 values : Min,P25(Q1),Median,P75(Q3),Max"))]
        val = featuretype[KEYS[idx]]
        for v in val.split(','):
            txt = v if idx == 0 else v + ' ' + str(featureval(ss.df, v))
            items.append(html.Div([html.B(txt), html.Br(), featuredict[v]], className="mb-2"))
        tabs.append(dbc.Tab(items, label=TABS[idx], className="mt-2"))
    return dbc.Tabs(tabs)
