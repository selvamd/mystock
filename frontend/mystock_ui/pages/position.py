"""Position (legacy ``position``): PnL, PnL by legs and trades of one position."""
from dash import Input, Output, State, callback, html, no_update

from .. import data
from .common import guard, note, select, slider, table

NAME = "Position"


@guard
def layout(pc):
    por = data.get_portfolio(pc["login"], pc["token"])
    keys = list(por.keys())
    if not keys:
        return note("No open positions")
    por.spot()
    vol0 = float(por(keys[0]).lvd.vol) * 100.0  # slider starts at the live vol (as legacy)
    return html.Div([
        select("pos-choice", "Position", keys, persist=False),
        slider("pos-vol", "Set Market Volatility", 0.0, 150.0, vol0, step=0.01),
        html.Div(id="pos-out"),
    ])


def default_vol(session, option):
    por = data.get_portfolio(session["login"], session["token"])
    por.spot()
    return float(por(option).lvd.vol) * 100.0


@callback(Output("pos-vol", "value"), Input("pos-choice", "value"), State("session-store", "data"))
def _vol(option, session):
    if not option or not session:
        return no_update
    try:
        return default_vol(session, option)
    except Exception:  # noqa: BLE001
        return no_update


@guard
def render(session, option, ivol):
    if not option:
        return html.Div()
    por = data.get_portfolio(session["login"], session["token"])
    por.spot()
    lvd = por(option).lvd
    if ivol is None:
        ivol = float(lvd.vol) * 100.0
    por(option).set(lvd.last, ivol / 100.0, lvd.ymd)
    out = [note('PNL for ' + option), table(por.pnl(option))]
    out.append(note('PNL by legs'))
    # LEGACY: por.pnlbylegs(option) filters on the 'pos' column
    df = por.pnlbylegs(option)
    df = df['key unrealized realized qty openpx spotpx cost value'.split()]
    out.append(table(df))
    out.append(note('Trades'))
    df = por.trades(option)
    df = df.copy().set_index("trades_id")
    out.append(table(df))
    return html.Div(out)


@callback(Output("pos-out", "children"), Input("pos-choice", "value"), Input("pos-vol", "value"),
          State("session-store", "data"))
def _cb(option, ivol, session):
    if not session:
        return html.Div()
    return render(session, option, ivol)
