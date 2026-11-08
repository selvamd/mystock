"""Portfolio (legacy ``portfolio``): PnL, OTM alerts, manage limits and greek summaries."""
from dash import Input, Output, State, callback, html

from .. import data
from .common import guard, note, table, text_input

NAME = "Portfolio"


@guard
def layout(pc):
    por = data.get_portfolio(pc["login"], pc["token"])
    por.spot()
    vals = ""
    for k in por.keys():
        vals += "," + k + '=' + str(100.0 * por(k).lvd.vol)
    return html.Div([
        text_input("port-vols", "Volatility Overrides", value=vals[1:], persist=False),
        html.Div(id="port-out"),
    ])


@guard
def render(session, vols):
    por = data.get_portfolio(session["login"], session["token"])
    por.spot()
    for kv in (vols or "").split(","):
        if kv.strip() == "":
            continue
        k, v = kv.split("=")
        lvd = por(k).lvd
        por(k).set(lvd.last, float(v) / 100.0, lvd.ymd)
    out = [note('PNL Summary')]
    pnl = por.pnl()
    out.append(table(pnl.sort_values(['open', 'key'], ascending=[False, True]) if len(pnl) else pnl))
    out.append(note('OTM Alerts'))
    df = por.alert()
    out.append(table(df.sort_values(['key', 'strike'], ascending=[True, True]) if len(df) else df))
    out.append(note('Manage Limits'))
    if len(por.keys()) > 0:
        out.append(table(por.manage().sort_values("pos callput".split())))
    for g in ("delta", "gamma", "theta"):
        out.append(note(g.capitalize() + ' Summary'))
        out.append(table(por.greek(g)))
    return html.Div(out)


@callback(Output("port-out", "children"), Input("port-vols", "value"), State("session-store", "data"))
def _cb(vols, session):
    if not session:
        return html.Div()
    return render(session, vols)
