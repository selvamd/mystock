"""Page smoke tests: every page's layout/render function against the fake backend,
plus logged-out state and the error paths (backend down, UPSTREAM, FORBIDDEN)."""
import socket

import dash_bootstrap_components as dbc
import pytest

from mystock_ui import api, client, data
from mystock_ui import app as appmod
from mystock_ui.client import ApiError
from mystock_ui.pages import (PAGES, call_put, close_trade, distribution, dynamic_reports, help, login,
                              market_summary, movers, open_trade, plots, portfolio, position, pricer, query, screens,
                              shadow, smart_screens, stock_overview, stock_time_charts, trade_sim, trend)
from mystock_ui.pages.common import REGISTRY, ss_call


def walk(c):
    yield c
    ch = getattr(c, "children", None)
    if ch is None:
        return
    for x in ch if isinstance(ch, (list, tuple)) else [ch]:
        if hasattr(x, "_type") or hasattr(x, "children"):
            yield from walk(x)


def alerts(c):
    return [x for x in walk(c) if isinstance(x, dbc.Alert)]


def alert_text(c):
    return " ".join(str(a.children) for a in alerts(c))


def ok(c):
    a = alerts(c)
    assert not a, f"unexpected alert(s): {alert_text(c)}"
    return c


def has_type(c, name):
    return any(type(x).__name__ == name for x in walk(c))


# ---------------------------------------------------------------- shell / auth
def test_page_names_and_order():
    assert [n for n, _, _ in PAGES] == [
        "Login", "Market Summary", "Trend", "Query", "Screens", "Smart Screens", "Movers", "Distribution",
        "Stock Overview", "Stock Time Charts", "Dynamic Reports", "Call/Put Data", "Pricer", "Trade Sim", "Shadow",
        "Open Trade", "Close Trade", "Portfolio", "Position", "Plots", "Help"]


def test_logged_out_only_login(live):
    for _, path, _ in PAGES:
        out = appmod.render_page(path, None, "SPY")
        assert any(getattr(x, "id", None) == "login-submit" for x in walk(out))
    nav = appmod.sidebar("/portfolio", None)
    links = [x for x in walk(nav) if isinstance(x, dbc.NavLink)]
    assert [l.children for l in links] == ["Login"]
    nav = appmod.sidebar("/portfolio", {"token": "t", "login": "demo"})
    assert len([x for x in walk(nav) if isinstance(x, dbc.NavLink)]) == 21


def test_login_logout(live):
    sess, stock, msg = login.do_login("demo", "bad")
    assert alerts(msg) and "Invalid login" in alert_text(msg)
    sess, stock, msg = login.do_login("demo", "demo")
    assert sess["login"] == "demo" and len(sess["token"]) == 32 and stock == "SPY"
    page = ok(login.layout({"token": sess["token"], "login": "demo"}))
    assert has_type(page, "Graph")  # VIX chart
    assert login.do_logout(sess) is None
    assert sess["token"] not in live.fx.sessions


def test_every_page_layout_renders(pc):
    for name, path, mod in PAGES:
        out = appmod.render_page(path, {"token": pc["token"], "login": "demo"}, "AAPL")
        ok(out)


# ------------------------------------------------------------- market pages
def test_market_pages(pc):
    for g in market_summary.GROUPS:
        out = ok(market_summary.render("mktcap > 0", g, "weekpnl"))
        assert has_type(out, "AgGrid") and has_type(out, "Graph")
    ok(trend.render("mktcap > 0"))
    for choice in screens.CHOICES:
        ok(screens.render(choice))
    ok(smart_screens.render(smart_screens.QRYTXT))
    ok(smart_screens.render("aapl,msft | mktcap"))
    assert smart_screens.help_doc(True).children
    for m in movers.METRICS:
        ok(movers.render(m, -1.0, 1.0, "All"))
    ok(movers.render("weekpnl", -1.0, 1.0, "Tech"))
    ok(distribution.render("qoqpnl", "mktcap >= 0"))
    ok(distribution.render("optvol - histvol", "Mktcap >= 0"))


def test_query_page(pc):
    assert query.chosen_value("Watch") == "aapl,msft,goog"
    ok(query.render("mktcap > 100 and pe < 50", "-weekpnl"))
    ok(query.render("aapl,msft,goog", ""))
    ok(query.render("aapl.mktvol", ""))
    # LEGACY: a bare ticker hits featuredict[...] for columns without a description -> message + params
    assert "Invalid request" in alert_text(query.render("aapl", ""))
    out = query.render("", "")
    assert has_type(out, "AgGrid")  # query params table
    bad = query.render("mktcap >>> 3", "")
    assert "Invalid request" in alert_text(bad) and has_type(bad, "AgGrid")
    inj = query.render("ticker.__class__ == 1", "")
    assert "not allowed" in alert_text(inj)


def test_stock_pages(pc):
    ok(stock_overview.render_urls("aapl"))
    for tab in stock_overview.TABS:
        ok(stock_overview.render_tab("AAPL", tab))
    for fx in stock_time_charts.CHARTS:
        out = stock_time_charts.render(fx, "AAPL", ["MOMDIP", "CCI", "PNLDIR"], 12, 2)
        ok(out)
        assert has_type(out, "Graph"), fx
    ok(stock_time_charts.render("swing", "AAPL/30", [], 12, 2))
    out = ok(call_put.render("AAPL", "implied"))
    assert has_type(out, "AgGrid")
    for m in call_put.MTYPES:
        ok(call_put.render("AAPL", m))
    out = ok(shadow.render("AAPL", "", "2026-01-02"))
    out = ok(shadow.render("AAPL", "0.35,0.45", "2026-01-02"))
    assert live_request(pc, "backtest.shadow")["args"] == {"ticker": "AAPL", "initdt": "2026-01-02", "optvol": 0.35, "riskvol": 0.45}


_BACKEND = {}


@pytest.fixture(autouse=True)
def _remember_backend(backend):
    _BACKEND["fb"] = backend


def live_request(pc, op):
    """Last request the fake backend received for ``op``."""
    return [r for r in _BACKEND["fb"].requests if r["op"] == op][-1]


def test_dynamic_reports(pc):
    df = data.get_dynamic_reports_data()
    for prompt in df.showtext:
        params = dynamic_reports.render_params(prompt, "AAPL")
        out = dynamic_reports.render(prompt, ["AAPL"], [3])
        if prompt.startswith("nosuchfunc"):
            assert "Unknown report function 'nosuchfunc'" in alert_text(out)
        elif prompt.startswith("summary"):
            ok(out)  # summary() with its defaults returns (fig, df): both are shown
            assert has_type(out, "Graph") and has_type(out, "AgGrid")
        else:
            ok(params)
            ok(out)


def test_registry_covers_legacy_dispatch(pc):
    ss = data.get_screener()
    for name, owner in REGISTRY.items():
        obj = ss if owner == "" else getattr(ss, owner)
        assert callable(getattr(obj, name)), name
    assert ss_call("zacks", "AAPL", False).startswith("https://www.zacks.com/")


# ------------------------------------------------------------ option pages
def test_pricer_all_strategies(pc):
    last, maxlast, ivol = pricer.slider_defaults("AAPL")
    assert maxlast == pytest.approx(2 * last)
    for senti in pricer.SENTIMENTS:
        for stg in pricer.strategies(senti):
            out, deltas, ph = pricer.render("AAPL", senti, stg, last, ivol, 45, "", False)
            ok(out)
            assert has_type(out, "Graph"), (senti, stg)
    out, deltas, ph = pricer.render("AAPL", "Neutral", "condor", last, ivol, 45, "", True)
    assert isinstance(deltas, str) and len(deltas.split(",")) == 4
    out2, _, _ = pricer.render("AAPL", "Neutral", "condor", last, ivol, 45, deltas, True)
    ok(out2)


def test_trade_sim(pc):
    for m in trade_sim.MTYPES:
        out = ok(trade_sim.render("AAPL", "B30D90P2@1.10,S30D95P2@2.40", "B100P1@0", m, 100.0, 30.0, 45, 5))
        assert has_type(out, "Graph")
    assert trade_sim.render("AAPL", "", "", "pnl", 100.0, 30.0, 45, 5).children in (None, [])


# ------------------------------------------------------- portfolio pages
def test_portfolio_pages(pc, session):
    ok(portfolio.render(session, None))
    vals = portfolio.layout(pc)
    ok(vals)
    ok(portfolio.render(session, "aapl0=45"))
    ok(position.render(session, "aapl0", None))
    ok(position.render(session, "aapl0", 40.0))
    assert position.default_vol(session, "aapl0") > 0
    for t in plots.PLOTS:
        out = ok(plots.render(session, t, "aapl0", 35.0, 5, "B100P1@0" if t == "pnl" else ""))
        assert has_type(out, "Graph"), t


def test_open_and_close_trade(pc, session, live):
    n0 = len(live.fx.trades)
    out = open_trade.submit(session, "MSFT", "20261101/20261218", "B300P@2.39,S310P@3.20", "1")
    assert "Trade Submitted" in alert_text(out)
    assert len(live.fx.trades) == n0 + 2
    booked = [r for r in live.requests if r["op"] == "trades.book"][-2:]
    assert booked[0]["args"]["legsym"] == "MSFT@300@261218@B1P" and booked[0]["args"]["position"] == "msft0"
    assert booked[1]["args"]["buysell"] == "S" and booked[1]["args"]["portfolio"] == "p0"
    assert booked[0]["args"]["tradedt"] == "2026-11-01"
    page = ok(close_trade.layout(pc))
    key = next(x for x in walk(page) if getattr(x, "id", None) == "ctrade-key").value
    px, qty = close_trade.defaults(session, key)
    out = close_trade.submit(session, key, "20261105", px, qty)
    assert "Trade Submitted" in alert_text(out)
    bad = open_trade.submit(session, "MSFT", "garbage", "x", "1")
    assert alerts(bad)


def test_help(pc):
    out = ok(help.layout(pc))
    assert len([x for x in walk(out) if isinstance(x, dbc.Tab)]) == 8


# ------------------------------------------------------------- error paths
def test_backend_down_is_friendly(live, pc):
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    client.set_client(client.Client("127.0.0.1", port, "k", connect_timeout=1, read_timeout=1))
    data.invalidate()
    for _, path, _ in PAGES[1:]:
        out = appmod.render_page(path, {"token": pc["token"], "login": "demo"}, "AAPL")
        txt = alert_text(out)
        if alerts(out):
            assert "not reachable" in txt, (path, txt)
    assert "not reachable" in alert_text(market_summary.render("mktcap > 0", "Sect", "weekpnl"))
    assert "not reachable" in alert_text(login.layout(pc))
    sess, _, msg = login.do_login("demo", "demo")
    assert "not reachable" in alert_text(msg)


def test_upstream_errors_are_friendly(live, pc):
    data.get_screener()
    live.fail_ops.update({"news.list": "UPSTREAM", "options.chain": "UPSTREAM", "fundamentals.statements": "UPSTREAM"})
    for tab in ("News", "Options", "Fundamentals"):
        t = alert_text(stock_overview.render_tab("AAPL", tab))
        assert "upstream data provider" in t, tab
    ok(stock_overview.render_tab("AAPL", "Price Chart"))  # other tabs unaffected
    assert "upstream data provider" in alert_text(call_put.render("AAPL", "implied"))


def test_vix_upstream_keeps_app_usable(live, pc):
    live.fail_ops["market.vix"] = "UPSTREAM"
    out = login.layout(pc)
    assert "VIX" in alert_text(out) and has_type(out, "Button")
    ok(market_summary.render("mktcap > 0", "Sect", "weekpnl"))


def test_expired_session_is_friendly(live, pc):
    bad = {"token": "0" * 32, "login": "demo"}
    assert "session has expired" in alert_text(portfolio.render(bad, None))
    assert "session has expired" in alert_text(position.layout({**bad, "stock": "AAPL"}))


def test_monkeypatched_api_errors(live, pc, monkeypatch):
    data.get_screener()

    def boom(*a, **k):
        raise ApiError("UPSTREAM", "polygon down")

    monkeypatch.setattr(api, "news_list", boom)
    assert "polygon down" in alert_text(stock_overview.render_tab("AAPL", "News"))
    monkeypatch.setattr(api, "quote_live", lambda *a, **k: (_ for _ in ()).throw(ApiError("INTERNAL", "x")))
    assert "Backend error INTERNAL" in alert_text(stock_overview.render_urls("AAPL"))


def test_unknown_ticker_is_friendly(pc):
    out = stock_time_charts.render("stock", "ZZZZ", [], 12, 2)
    assert alerts(out)
