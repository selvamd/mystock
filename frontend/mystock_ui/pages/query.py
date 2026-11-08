"""Query (legacy ``query``): saved queries + free-form screener expression."""
from dash import Input, Output, callback, html, no_update

from .. import data
from ..legacy.myutils import feature
from .common import check_expr, error_box, found, guard, note, select, ss_call, table, text_input

NAME = "Query"


@guard
def layout(pc):
    queries = data.get_queries()
    names = list(queries.name.values)
    return html.Div([
        select("query-choice", "Select Query", names),
        text_input("query-qry", "Query", placeholder="mktcap > 10 and sect == 'TECH' and ..."),
        text_input("query-sort", "Sort include", value="-weekpnl", placeholder="Metric or Expr"),
        html.Div(id="query-out"),
    ])


def chosen_value(choice):
    queries = data.get_queries()
    return queries[queries.name == choice].value.max().replace('|', ',')


@callback(Output("query-qry", "value"), Input("query-choice", "value"), prevent_initial_call=True)
def _choice(choice):
    if not choice:
        return no_update
    try:
        return chosen_value(choice)
    except Exception:  # noqa: BLE001
        return no_update


def params():
    return html.Div([note('Query Params'), table(feature('show'))])


@guard
def render(qry, sqry):
    qry = qry or ""
    qry = qry[0:1].lower() + qry[1:]
    sqry = (sqry or "").lower().strip()
    if len(qry) == 0:
        return params()
    try:
        check_expr(qry)
        check_expr(sqry)
        if len(qry.split()) > 1:
            df = ss_call("squery", qry) if sqry == '' else ss_call("squery", qry, sqry, sqry)
            df = df.copy().set_index('ticker')
        elif len(qry.split(",")) > 1:
            aqry = "ticker in ['"
            for tick in qry.upper().split(","):
                aqry += tick + "','"
            aqry += "']"
            df = ss_call("squery", aqry) if sqry == '' else ss_call("squery", aqry, sqry)
            df = df.copy().set_index('ticker')
        else:
            q = qry.upper().split(".") + ['']
            q[0] += "." + q[1]
            df = data.get_screener()[q[0]]
        return html.Div([found(df), table(df)])
    except Exception as e:  # noqa: BLE001 - legacy: message + query params
        return html.Div([error_box(e), params()])


@callback(Output("query-out", "children"), Input("query-qry", "value"), Input("query-sort", "value"))
def _cb(qry, sqry):
    return render(qry, sqry)
