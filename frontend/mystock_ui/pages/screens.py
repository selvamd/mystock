"""Screens (legacy ``screens``)."""
from dash import Input, Output, callback, html

from .common import found, guard, select, ss_call, table

NAME = "Screens"
CHOICES = "Condor,Etfs,Portfolio,Momentum,Liquid,NextWeekPNL,CallSpread,PutSpread".split(',')


def layout(pc):
    return html.Div([select("screens-choice", "Choose a Report", CHOICES), html.Div(id="screens-out")])


@guard
def render(choice):
    if choice not in CHOICES:
        return html.Div()
    if choice == 'Portfolio':
        df = ss_call("portfolio", 'MW')
    else:
        df = ss_call(choice.lower())
        df = df.copy().set_index('ticker')
    out = [found(df)]
    if choice == 'NextWeekPNL':
        out.append(html.Small("Model predictions are not exposed by the backend; nextweekpnl is 0 (legacy fallback)."))
    out.append(table(df))
    return html.Div(out)


@callback(Output("screens-out", "children"), Input("screens-choice", "value"))
def _cb(choice):
    return render(choice)
