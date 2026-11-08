"""Plots (legacy ``plots``): position pnl/greek plots with overlay trades, or the cone chart."""
from dash import Input, Output, State, callback, html, no_update

from .. import data
from ..legacy.option import datediff, reldate
from ..legacy.positions import tposition
from .common import graph, guard, note, pending, select, slider, text_input, upxvol

NAME = "Plots"
PLOTS = ["pnl", "cone", "delta", "gamma", "theta"]


@guard
def layout(pc):
    por = data.get_portfolio(pc["login"], pc["token"])
    keys = list(por.keys())
    if not keys:
        return note("No open positions")
    _, pos = position_at_market({"login": pc["login"], "token": pc["token"]}, keys[0])
    vol0 = float(pos.lvd.vol) * 100.0  # slider starts at the market vol (as legacy)
    return html.Div([
        select("plots-type", "Plot Type", PLOTS),
        select("plots-pos", "Position", keys, persist=False),
        slider("plots-vol", "Set Market Volatility", 0.0, 150.0, vol0, step=0.01),
        html.Div([
            slider("plots-incr", "Increment", 0, 25, 5, step=1),
            text_input("plots-sim", "Overlay Trades", placeholder="B100P10@0.."),
        ], id="plots-extra"),
        html.Div(id="plots-out"),
    ])


def position_at_market(session, posval):
    por = data.get_portfolio(session["login"], session["token"])
    pos = por(posval)
    upx, ivol = upxvol(pos.ticker)
    pos.set(upx, ivol / 100.0, reldate(0))
    return por, pos


@callback(Output("plots-vol", "value"), Input("plots-pos", "value"), State("session-store", "data"))
def _vol(posval, session):
    if not posval or not session:
        return no_update
    try:
        _, pos = position_at_market(session, posval)
        return float(pos.lvd.vol) * 100.0
    except Exception:  # noqa: BLE001
        return no_update


@callback(Output("plots-extra", "style"), Input("plots-type", "value"))
def _extra(t):
    return {"display": "none"} if t == "cone" else {}


@guard
def render(session, type, posval, ivol, incr, simtradenew):
    if type not in PLOTS or not posval:
        return html.Div()
    por, pos = position_at_market(session, posval)
    lvd = pos.lvd
    if ivol is None:
        ivol = float(lvd.vol) * 100.0
    pos.set(lvd.last, ivol / 100.0, lvd.ymd)
    if type != "cone":
        simtradestr = pos.getsimtrades()
        incr = int(incr if incr is not None else 5)
        simtradenew = simtradenew or ""
        out = []
        if len(por.keys()) > 0:
            t = tposition('x', 'x', 'x', 'x')
            t.ticker, t.exprdt = pos.ticker, pos.exprdt
            t.set(lvd.last, ivol / 100.0, lvd.ymd)
            t.simtrades(simtradestr)
            if len(simtradenew) > 0:
                arr = pending(simtradenew, lvd.last, ivol / 100.0, datediff(pos.exprdt))
                out.append(note("Pending leg trades: " + " ".join(arr)))
                t.simtrades(",".join(arr))
            out.append(graph(t.plot(type, None, int(incr))))
        return html.Div(out)
    # LEGACY BUG FIXED: sapp called por.cone(pos, ss), passing the position object where
    # myposition.cone expects a key string, so the cone chart always failed.
    return graph(pos.cone(data.get_screener()))


@callback(Output("plots-out", "children"), Input("plots-type", "value"), Input("plots-pos", "value"),
          Input("plots-vol", "value"), Input("plots-incr", "value"), Input("plots-sim", "value"),
          State("session-store", "data"))
def _cb(type, posval, ivol, incr, sim, session):
    if not session:
        return html.Div()
    return render(session, type, posval, ivol, incr, sim)
