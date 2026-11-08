"""Dynamic Reports (legacy ``get_dynamic_reports``).

Legacy built a python string ``ss('func', args...)`` and ``eval``'d it; here the report's
``function`` is looked up in the explicit registry (:data:`common.REGISTRY`) and unknown
names show an error.
"""
import pandas as pd
from dash import ALL, Input, Output, callback, dcc, html

from .. import data
from .common import graph, guard, slider, ss_call, stock_input, table

NAME = "Dynamic Reports"


@guard
def layout(pc):
    df = data.get_dynamic_reports_data()
    opts = list(df.showtext.values)
    return html.Div([
        dcc.Dropdown(id="dr-choice", options=opts, value=None, placeholder="Choose a report",
                     persistence=True, persistence_type="session", className="mb-2"),
        dcc.Store(id="dr-stock-default", data=pc.get("stock") or ""),
        html.Div(id="dr-params"),
        html.Div(id="dr-out"),
    ])


def parse_params(params):
    out = []
    for param in str(params or "").split("#"):
        if "=" not in param:
            continue
        name, val = param.split("=", 1)
        out.append((name.strip(), val.strip()))
    return out


def report_row(prompt):
    df = data.get_dynamic_reports_data()
    df = df[df.showtext == prompt]
    return None if len(df) == 0 else df.iloc[0]


@guard
def render_params(prompt, stock):
    if prompt is None:
        return html.Div()
    row = report_row(prompt)
    if row is None:
        return html.Div()
    widgets = []
    for name, val in parse_params(row.params):
        if name == 'ticker':
            widgets.append(stock_input("dynrep", stock or val))
        if name == 'month':
            widgets.append(slider({"type": "dr-month", "name": "month"}, "Set Months Elapsed", 0, 100, int(val), step=1))
    return html.Div(widgets)


@guard
def render(prompt, tickers, months):
    if prompt is None:
        return html.Div()
    row = report_row(prompt)
    if row is None:
        return html.Div()
    args = []
    ti, mi = 0, 0
    for name, val in parse_params(row.params):
        if name == 'ticker':
            v = tickers[ti] if ti < len(tickers) and tickers[ti] is not None else val
            ti += 1
            if v != '': args += [v]
        if name == 'month':
            dte = months[mi] if mi < len(months) and months[mi] is not None else int(val)
            mi += 1
            args += [int(dte) * 30]
    res = ss_call(str(row.function).strip(), *args)
    if isinstance(res, tuple):  # e.g. summary -> (fig, df); legacy showed nothing for tuples
        return html.Div([render_one(r) for r in res])
    return render_one(res)


def render_one(res):
    if hasattr(res, 'show'):
        return graph(res)
    if isinstance(res, pd.DataFrame):
        return table(res)
    return html.Div()


@callback(Output("dr-params", "children"), Input("dr-choice", "value"), Input("dr-stock-default", "data"))
def _params(prompt, stock):
    return render_params(prompt, stock)


@callback(Output("dr-out", "children"), Input("dr-choice", "value"),
          Input({"type": "stock-input", "page": ALL}, "value"),
          Input({"type": "dr-month", "name": ALL}, "value"))
def _cb(prompt, tickers, months):
    return render(prompt, tickers or [], months or [])
