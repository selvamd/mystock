"""Shared UI helpers for the Dash pages (replacements for the Streamlit widgets/helpers in sapp.py)."""
from __future__ import annotations

import functools
import logging
import math

import dash_ag_grid as dag
import dash_bootstrap_components as dbc
import numpy as np
import pandas as pd
from dash import dcc, html

from .. import data
from ..client import ApiError
from ..legacy.myutils import single_option_pricer

log = logging.getLogger(__name__)


# ----------------------------------------------------------------- error handling
def friendly(e: Exception) -> str:
    if isinstance(e, ApiError):
        if e.code in ("UNAVAILABLE", "TIMEOUT"):
            return f"The backend is not reachable right now ({e.message}). Please try again shortly."
        if e.code == "UPSTREAM":
            return f"The upstream data provider is unavailable ({e.message}). Please try again later."
        if e.code == "FORBIDDEN":
            return "Your session has expired. Please log out and log in again."
        if e.code == "NOT_FOUND":
            return f"Not found: {e.message}"
        if e.code == "BAD_REQUEST":
            return f"The backend rejected the request: {e.message}"
        return f"Backend error {e.code}: {e.message}"
    if isinstance(e, NotImplementedError):
        return f"Not available: {e}"
    if isinstance(e, UnknownFunction):
        return str(e)
    return "Invalid request. Try again"  # legacy message


def error_box(e: Exception):
    color = "warning" if isinstance(e, (ApiError, NotImplementedError, UnknownFunction)) else "danger"
    return dbc.Alert(friendly(e), color=color, className="my-2")


def guard(fn):
    """Render ``fn``'s output, or a friendly alert instead of a traceback."""

    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except Exception as e:  # noqa: BLE001 - UI boundary
            if not isinstance(e, (ApiError, NotImplementedError, UnknownFunction)):
                log.exception("page callback %s failed", fn.__name__)
            return error_box(e)

    return wrapper


def guard_multi(n_outputs, error_slot=0):
    """Like :func:`guard` for callbacks with several outputs (alert goes into ``error_slot``)."""
    from dash import no_update

    def deco(fn):
        @functools.wraps(fn)
        def wrapper(*args, **kwargs):
            try:
                return fn(*args, **kwargs)
            except Exception as e:  # noqa: BLE001
                if not isinstance(e, (ApiError, NotImplementedError, UnknownFunction)):
                    log.exception("page callback %s failed", fn.__name__)
                out = [no_update] * n_outputs
                out[error_slot] = error_box(e)
                return tuple(out)

        return wrapper

    return deco


# ----------------------------------------------------------- explicit registry
class UnknownFunction(Exception):
    pass


# Replacement for legacy ``ss("func", ...)``/eval dispatch. Same lookup order as
# stock_screener.__call__ (self, zmkt, zscr, zsym, zweb, zviz): first owner wins.
_REGISTRY_SPEC = [
    ("", "columns mode spotprice spot spotvol movers watch quote query squery slice"),
    ("zmkt", "desc summary groupsummary mktsummary portfolio sector mktsize industry topn yymetric"),
    ("zscr", "rebounds blazers soldoffs laggards leaders buys pe corr lowtailrisk volchgopt volchg steady dips "
             "liquid nextweekpnl etfs condor callspread putspread momentum lowlongvol"),
    ("zsym", "like volsurface volhist financials stk mktcompare strikes fdeltas surface dcalls dputs calls puts "
             "tailriskdata opricer mktshifts indleader pricelevels price_action"),
    ("zweb", "macro finviz dataroma valuesider ostrat barchart mktchemleon zacks morningstar yahoo yhoption "
             "tipranks chartmill fidoption"),
    ("zviz", "distribution heat tailrisk month_ohlc cone swingrange swing tsdrawdown tsdrawdowndist bband rsi "
             "pxdist macd mvavg scatter drscatter drtrend drfreq tsvoltime tsvolgrowth ohlc tsgrowth tsstock "
             "tsmetrics tsmktshare tsmktvalue ffe"),
]
REGISTRY: dict[str, str] = {}
for _owner, _names in _REGISTRY_SPEC:
    for _n in _names.split():
        REGISTRY.setdefault(_n, _owner)


def ss_call(func: str, *args):
    """``ss(func, *args)`` through the explicit registry (unknown names are an error)."""
    owner = REGISTRY.get(func)
    if owner is None:
        raise UnknownFunction(f"Unknown report function '{func}'.")
    ss = data.get_screener()
    obj = ss if owner == "" else getattr(ss, owner)
    return getattr(obj, func)(*args)


# ------------------------------------------------------- sapp.py helper ports
def upxvol(stk):
    """sapp.upxvol (verbatim logic)."""
    from ..legacy.svcreader import qryspot

    ss = data.get_screener()
    sdf = qryspot(stk.upper())
    upx = sdf.spotprice.max()
    ivol = sdf.spotvol.max()
    if ivol == 20.0: ivol = 0
    if upx == 0: upx = ss[stk + ".lastpx"].value.max()
    ivol = max(ss[stk + ".histvol"].value.max(), ivol)
    ivol = max(ss[stk + ".optvol"].value.max(), ivol)
    return upx, ivol


def urls(sym):
    """sapp.urls: header line + reference hyperlinks (plain links, no data fetch)."""
    ss = data.get_screener()
    sym = sym.split(",")[0]
    upx, ivol = upxvol(sym)
    head = ("" + sym + " [Last : " + str(upx) + ", Dir : " + str(round(ss[sym + ".pnldir"].value.max(), 2))
            + ", IV : " + str(round(ivol, 2)) + " " + str(round(ss[sym + ".triskvol"].value.max(), 2)) + "] ")
    links = []
    names = [(f, f.lower()) for f in "Yahoo MktChemleon BarChart ChartMill Morningstar Zacks Tipranks".split()]
    names += [("Fidelity", "fidoption")]
    names += [(f, f.lower()) for f in "Finviz Dataroma Valuesider OStrat".split()]
    for label, func in names:
        links += [html.A(label, href=getattr(ss.zweb, func)(sym, False), target="_blank", rel="noopener"), " | "]
    return html.Div([html.B(head), html.Br(), html.Span(links)], className="mb-2")


def pending(opentrades, last, ivol, dte):
    """sapp.pending (verbatim)."""
    arr = []
    for leg in opentrades.split(","):
        arr0, arr1 = leg.split("@")
        upx = last if arr1 == '0' else float(arr1)
        strike = arr0[1:]
        if 'D' in strike:
            sdte, strike = strike.split('D')
            dte = int(sdte)
        if 'P' in arr0:
            opx = single_option_pricer('put', upx, ivol, dte, float(strike.split('P')[0]), False)
        else:
            opx = single_option_pricer('call', upx, ivol, dte, float(strike.split('C')[0]), False)
        arr.append(arr0 + '@' + str(round(opx, 2)))
    return arr


# ------------------------------------------------------------------- widgets
def text_input(id_, label, value="", placeholder="", persist=True, type_="text"):
    return dbc.Row(
        [
            dbc.Label(label, html_for=id_ if isinstance(id_, str) else None, width=3),
            dbc.Col(
                dbc.Input(
                    id=id_, value=value, placeholder=placeholder, type=type_, debounce=True,
                    persistence=persist if isinstance(id_, str) else False, persistence_type="session",
                ),
                width=9,
            ),
        ],
        className="mb-2",
    )


def stock_input(page, value, label="Stock"):
    """Shared 'current stock' input (legacy session('global','stock')); synced to stock-store."""
    return text_input({"type": "stock-input", "page": page}, label, value=value or "", persist=False)


def select(id_, label, options, value=None, persist=True, multi=False):
    opts = [{"label": str(o), "value": o} for o in options]
    if value is None and not multi and options:
        value = options[0]
    return dbc.Row(
        [
            dbc.Label(label, width=3),
            dbc.Col(
                dcc.Dropdown(id=id_, options=opts, value=value, clearable=multi, multi=multi,
                             persistence=persist, persistence_type="session"),
                width=9,
            ),
        ],
        className="mb-2",
    )


def slider(id_, label, vmin, vmax, value, step=None, persist=False):
    return dbc.Row(
        [
            dbc.Label(label, width=3),
            dbc.Col(
                dcc.Slider(id=id_, min=vmin, max=vmax, value=value, step=step, marks=None,
                           tooltip={"placement": "bottom", "always_visible": True},
                           updatemode="mouseup", persistence=persist, persistence_type="session"),
                width=9,
            ),
        ],
        className="mb-3",
    )


def note(text):
    return html.P(text, className="mt-2 mb-1")


def bold(text):
    return html.B(text)


# -------------------------------------------------------------------- output
def _jsonable(df: pd.DataFrame) -> list[dict]:
    d = df.copy()
    for c in d.columns:
        if pd.api.types.is_datetime64_any_dtype(d[c]):
            d[c] = d[c].dt.strftime("%Y-%m-%d")
        elif pd.api.types.is_numeric_dtype(d[c]):
            d[c] = d[c].replace([np.inf, -np.inf], np.nan)
    d = d.astype(object).where(pd.notna(d), None)
    out = []
    for rec in d.to_dict("records"):
        clean = {}
        for k, v in rec.items():
            if isinstance(v, (np.integer,)):
                v = int(v)
            elif isinstance(v, (np.floating, float)):
                v = None if math.isnan(float(v)) else float(v)
            elif isinstance(v, (np.bool_,)):
                v = bool(v)
            elif v is not None and not isinstance(v, (str, int, float, bool)):
                v = str(v)
            clean[k] = v
        out.append(clean)
    return out


def table(df, index=None):
    """Replacement for ``st.dataframe``: AG Grid, index shown when it is meaningful."""
    if df is None:
        return html.Div()
    if isinstance(df, pd.Series):
        df = df.to_frame()
    d = df.copy()
    if index or (index is None and not isinstance(d.index, pd.RangeIndex)):
        d = d.reset_index()
    cols = []
    seen = {}
    for c in d.columns:
        name = str(c)
        if name in seen:
            seen[name] += 1
            name = f"{name}.{seen[name]}"
        else:
            seen[name] = 0
        cols.append(name)
    d.columns = cols
    coldefs = []
    for c in cols:
        cd = {"field": c, "headerName": c, "sortable": True, "filter": True, "resizable": True}
        if pd.api.types.is_float_dtype(d[c]):
            cd["valueFormatter"] = {"function": "params.value == null ? '' : d3.format(',.2f')(params.value)"}
            cd["type"] = "rightAligned"
        coldefs.append(cd)
    n = len(d)
    auto = n <= 20
    return dag.AgGrid(
        rowData=_jsonable(d),
        columnDefs=coldefs,
        columnSize="autoSize",
        dashGridOptions={
            "suppressFieldDotNotation": True,
            "domLayout": "autoHeight" if auto else "normal",
            "pagination": n > 100,
            "paginationPageSize": 100,
            "animateRows": False,
        },
        style={"height": None if auto else "520px", "width": "100%"},
        className="ag-theme-alpine mb-3",
    )


def graph(fig):
    if fig is None:
        return html.Div()
    return dcc.Graph(figure=fig, config={"displaylogo": False}, className="mb-3")


def found(df):
    return note("Found " + str(len(df)) + " matches")


def check_expr(expr):
    """Basic hardening for user-typed pandas query/eval expressions (the screener feature).

    ``DataFrame.query``/``eval`` is kept, but dunder access, ``@`` local-variable references,
    lambdas and imports are rejected before reaching pandas.
    """
    if expr is None:
        return expr
    low = str(expr).lower()
    for bad in ("__", "@", "lambda", "import", "getattr", "exec(", "eval("):
        if bad in low:
            raise BadExpression(f"Expression not allowed (contains '{bad}').")
    return expr


class BadExpression(UnknownFunction):
    pass
