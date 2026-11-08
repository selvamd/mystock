"""Page registry, same names and order as legacy ``page_names_to_funcs`` in sapp.py."""
import re

from . import (call_put, close_trade, distribution, dynamic_reports, help, login, market_summary, movers,
               open_trade, plots, portfolio, position, pricer, query, screens, shadow, smart_screens,
               stock_overview, stock_time_charts, trade_sim, trend)

MODULES = [login, market_summary, trend, query, screens, smart_screens, movers, distribution,
           stock_overview, stock_time_charts, dynamic_reports, call_put, pricer, trade_sim, shadow,
           open_trade, close_trade, portfolio, position, plots, help]


def slug(name):
    return "/" + re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


PAGES = [(m.NAME, slug(m.NAME), m) for m in MODULES]
BY_PATH = {p: m for _, p, m in PAGES}
