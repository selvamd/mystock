"""The copied legacy modules must (a) keep every top-level name of the originals and
(b) produce the same numbers as the original ``mystk/python`` modules on the same inputs.

The originals are imported straight from ``mystk/python`` (read-only), with stub modules for
``sqlalchemy``/``bs4`` and with their data functions monkeypatched to the same fixture frames.
"""
import ast
import datetime as dt
import importlib
import os
import sys
import types

import numpy as np
import pandas as pd
import pytest

from mystock_ui.devtools.fixtures import Fixtures
from mystock_ui.legacy import myutils as new_myutils
from mystock_ui.legacy import option as new_option
from mystock_ui.legacy import optionprice as new_optionprice
from mystock_ui.legacy import positions as new_positions
from mystock_ui.legacy import svcreader as new_svc
from mystock_ui.legacy import utils as new_utils

LEGACY_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "mystk", "python"))
NEW_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "mystock_ui", "legacy"))
MODULES = ["optionprice", "plots", "svcreader", "utils", "option", "positions", "myutils", "myglobal"]

pytestmark = pytest.mark.skipif(not os.path.isdir(LEGACY_DIR), reason="legacy mystk/python not present")


def _names(path):
    tree = ast.parse(open(path, encoding="utf-8").read())
    out = set()
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.ClassDef)):
            out.add(node.name)
            if isinstance(node, ast.ClassDef):
                for sub in node.body:
                    if isinstance(sub, ast.FunctionDef):
                        out.add(node.name + "." + sub.name)
        elif isinstance(node, ast.Assign):
            for t in node.targets:
                if isinstance(t, ast.Name):
                    out.add(t.id)
    return out


@pytest.mark.parametrize("mod", MODULES)
def test_every_legacy_name_still_exists(mod):
    old = _names(os.path.join(LEGACY_DIR, mod + ".py"))
    new = _names(os.path.join(NEW_DIR, mod + ".py"))
    missing = sorted(old - new)
    assert not missing, f"{mod}: missing {missing}"


@pytest.fixture(scope="module")
def legacy():
    """Import the original legacy modules (no network: data functions are patched per test)."""
    stubs = {}
    for name in ("sqlalchemy", "bs4"):
        if name not in sys.modules:
            m = types.ModuleType(name)
            if name == "bs4":
                m.BeautifulSoup = object
            stubs[name] = m
            sys.modules[name] = m
    saved = {n: sys.modules.pop(n) for n in MODULES if n in sys.modules}
    sys.path.insert(0, LEGACY_DIR)
    try:
        mods = {n: importlib.import_module(n) for n in MODULES if n != "myglobal"}
    finally:
        sys.path.remove(LEGACY_DIR)
    yield types.SimpleNamespace(**mods)
    for n in MODULES:
        sys.modules.pop(n, None)
    sys.modules.update(saved)
    for n in stubs:
        sys.modules.pop(n, None)


FX = Fixtures(today=dt.date.today())


def screener_frame():
    """The legacy 'out.csv' frame -- what request_csv('out') returns in both worlds."""
    return FX.screener_stocks()


# ----------------------------------------------------------------- option math
def test_optionprice_known_values():
    # Black-Scholes call S=K=100, r=5%, sigma=20%, 1y: 10.4506
    o = new_optionprice.Option(european=True, kind="call", s0=100.0, k=100.0, r=0.05, sigma=0.2, t=365)
    assert o.getPrice() == pytest.approx(10.4506, abs=1e-3)
    # binomial tree converges to BS for a European call
    assert o.getPrice(method="BT", iteration=1000) == pytest.approx(10.4506, abs=0.02)
    # American put >= European put (BS put = 5.5735)
    a = new_optionprice.Option(european=False, kind="put", s0=100.0, k=100.0, r=0.05, sigma=0.2, t=365)
    assert a.getPrice(method="BT", iteration=1000) == pytest.approx(6.09, abs=0.03)


def test_option_functions_match_legacy(legacy):
    for typ, strike, last, vol, days in [("call", 100, 100.0, 0.3, 30), ("put", 95, 100.0, 0.25, 45),
                                         ("call", 120, 110.0, 0.5, 7), ("put", 300, 280.0, 0.2, 90)]:
        lv_new, lv_old = new_option.ovar(last, vol, days), legacy.option.ovar(last, vol, days)
        assert new_option.price(typ, strike, lv_new) == legacy.option.price(typ, strike, lv_old)
        assert new_option.delta(typ, strike, lv_new) == legacy.option.delta(typ, strike, lv_old)
        assert new_option.theta(typ, strike, lv_new) == legacy.option.theta(typ, strike, lv_old)
        assert new_option.gamma(typ, strike, new_option.ovar(last, vol, days)) == \
            legacy.option.gamma(typ, strike, legacy.option.ovar(last, vol, days))
        assert new_option.fdelta(strike, last, vol, days) == legacy.option.fdelta(strike, last, vol, days)
        assert new_myutils.single_option_pricer(typ, last, vol, days, strike, False) == \
            legacy.myutils.single_option_pricer(typ, last, vol, days, strike, False)


def test_strike_solvers_match_legacy(legacy):
    lv = (250.0, 0.35, 45)
    for d in (10, 25, 30, 40):
        new_c = new_option.callstrike(d, new_option.ovar(*lv))
        assert new_c == legacy.option.callstrike(d, legacy.option.ovar(*lv))
        assert new_option.putstrike(d, new_option.ovar(*lv)) == legacy.option.putstrike(d, legacy.option.ovar(*lv))
        # the solved strike really has the requested (call) delta, within the solver's eps
        assert new_option.delta("call", new_c, new_option.ovar(*lv)) == pytest.approx(50 - d, abs=0.011)
    for pxt in ("-30d-10", "-30d", "30d", "30d+10", "+0", "-5", "360"):
        assert new_option.Stgy.strike(pxt, new_option.ovar(*lv), 5) == legacy.option.Stgy.strike(pxt, legacy.option.ovar(*lv), 5)
    assert new_option.strike_approx(30, new_option.ovar(*lv)) == legacy.option.strike_approx(30, legacy.option.ovar(*lv))
    for s in dir(legacy.option.Stgy):
        if not s.startswith("_") and isinstance(getattr(legacy.option.Stgy, s), str):
            assert new_option.Stgy.template(s) == legacy.option.Stgy.template(s)
            assert new_option.Stgy.init(s) == legacy.option.Stgy.init(s)


# ------------------------------------------------------- screener transforms
def test_derived_columns_match_legacy(legacy):
    for fn in ("qdropratio", "dpnl", "cross"):
        a = getattr(new_svc, fn)(screener_frame())
        b = getattr(legacy.svcreader, fn)(screener_frame())
        pd.testing.assert_frame_equal(a, b)
    a = new_svc.tier(screener_frame(), 'eps', [0, 10, 20, 50], ['NONE', 'LOW', 'FAIR', 'HIGH', 'XTREME'], 'petype')
    b = legacy.svcreader.tier(screener_frame(), 'eps', [0, 10, 20, 50], ['NONE', 'LOW', 'FAIR', 'HIGH', 'XTREME'], 'petype')
    pd.testing.assert_frame_equal(a, b)


def test_derived_formulas_by_hand():
    df = pd.DataFrame({"yoypnl": [30.0, 10.0], "qoqpnl": [10.0, 20.0], "mompnl": [5.0, -1.0],
                       "ema20": [95.0, 105.0], "ema60": [90.0, 110.0], "qdrop": [20.0, 5.0],
                       "qdropavg": [10.0, 10.0], "histvol": [25.0, 50.0], "ticker": ["A", "SPY"],
                       "pe": [5.0, 60.0]})
    out = new_svc.dpnl(df.copy())
    # y=(30-10)*4/3=26.67, q=(10-5)*6=30, m=60 -> 26.67 ; y=-13.3, q=126, m=-12 -> -13.33
    assert out.dpnl.round(2).tolist() == [26.67, -13.33]
    assert new_svc.cross(df.copy()).cross.tolist() == ["Bullish", "Bearish"]
    assert new_svc.qdropratio(df.copy()).qdropratio.tolist() == [pytest.approx(10 / 2.0), pytest.approx(-5 / 4.0)]
    assert new_svc.tier(df.copy(), "pe", [0, 10, 20, 50], ["NONE", "LOW", "FAIR", "HIGH", "XTREME"], "t").t.tolist() == ["LOW", "XTREME"]


class FakeCtx:
    def __init__(self, svc):
        df = screener_frame()
        # apply the globalcontext transforms with the given module
        df['summ'] = df.summ.str.replace('u', '↑').str.replace('d', '↓')
        df['mktcap'] = df['mktcap'] / 1_000_000_000
        df['sect'] = df.sect.str.upper()
        df['pe'] = df.apply(lambda row: 0 if row.eps <= 0 else row.lastpx / row.eps, axis=1)
        df = svc.tier(df, 'pe', [0, 10, 20, 50], ['NONE', 'LOW', 'FAIR', 'HIGH', 'XTREME'], 'petype')
        df = svc.tier(df, 'mktcap', [5, 10, 20, 50, 100, 250], ['5B', '10B', '20B', '50B', '100B', '250B', '250B+'], 'mktsize')
        df["recovery"] = df.qrebound * 100 / (df.qrebound + df.qdrop)
        df = svc.qdropratio(df)
        df = svc.cross(svc.dpnl(df))
        self.df = df
        self.port = svc.cross(svc.dpnl(FX.screener_portfolios()))
        self.lastdt = 20000101
        self.portfolio = types.MethodType(svc.globalcontext.portfolio, self)


def test_screener_functions_match_legacy(legacy):
    new_ss = new_myutils.stock_screener(FakeCtx(new_svc), False)
    old_ss = legacy.myutils.stock_screener(FakeCtx(legacy.svcreader), False)
    calls = [("movers", -1.0, "weekpnl", 1.0, None), ("squery", "mktcap > 10", "-weekpnl", "-weekpnl"),
             ("slice", "mktcap == 0", "lastpx,weekpnl", "weekpnl"), ("slice", "aapl,msft", None, "mktcap"),
             ("condor",), ("etfs",), ("momentum",), ("callspread",), ("putspread",), ("liquid",),
             ("portfolio", "MW"), ("mktsummary", "mktcap > 0", "perf"),
             ("mktsummary", "mktcap > 0", "trend"), ("desc", "qoqpnl", "mktcap > 0"),
             ("topn", "sect", "mktcap", 3), ("like", "AAPL"), ("stk", "AAPL"), ("quote", "aapl,msft")]
    for call in calls:
        a, b = new_ss(*call), old_ss(*call)
        pd.testing.assert_frame_equal(a, b, obj=str(call))
    fa, da = new_ss("summary", "sect", "weekpnl", "mktcap > 0")
    fb, db = old_ss("summary", "sect", "weekpnl", "mktcap > 0")
    pd.testing.assert_frame_equal(da, db)
    pd.testing.assert_frame_equal(new_ss["AAPL.mktvol"], old_ss["AAPL.mktvol"])
    for f in ("pnldir", "qdrop", "summ", "earnings"):
        assert new_myutils.featureval(new_ss.df, f) == legacy.myutils.featureval(old_ss.df, f)
    pd.testing.assert_frame_equal(new_myutils.feature("show"), legacy.myutils.feature("show"))
    pd.testing.assert_frame_equal(new_myutils.summary_perf(new_ss.df), legacy.myutils.summary_perf(old_ss.df))


# ------------------------------------------------------------ option surfaces
def av_frame():
    """Legacy Alpha Vantage HISTORICAL_OPTIONS frame (as returned after smart_cast_df)."""
    ch = FX.options_chain("AAPL")
    df = ch.rename(columns={"contract_id": "contractID"})
    df["type"] = df["type"].map({"C": "call", "P": "put"})
    df["date"] = df["asof"].dt.strftime("%Y%m%d").astype(int)
    df["expiration"] = df["expiration"].dt.strftime("%Y%m%d").astype(int)
    return df.drop(columns=["asof"])


def test_option_surface_and_summary_match_legacy(legacy, monkeypatch):
    for attr in ("implied", "delta", "price", "openint"):
        pd.testing.assert_frame_equal(new_utils.option_surface(av_frame(), "call", attr),
                                      legacy.utils.option_surface(av_frame(), "call", attr))
    monkeypatch.setattr(legacy.utils, "options_history", lambda sym: av_frame())
    monkeypatch.setattr(new_utils, "options_history", lambda sym: av_frame())
    pd.testing.assert_frame_equal(new_utils.options_summary("AAPL"), legacy.utils.options_summary("AAPL"))


def test_options_history_boundary_shape(live):
    df = new_utils.options_history("AAPL")
    ref = av_frame()
    assert set(ref.columns) <= set(df.columns)
    assert df["type"].isin(["call", "put"]).all()
    assert df["expiration"].dtype.kind == "i" and df["date"].dtype.kind == "i"


def test_finstmt_matches_legacy_alpha_vantage_path(live, legacy, monkeypatch):
    """Legacy finstmt parsed AV JSON; the new one rebuilds it from fundamentals.statements."""
    long = FX.fundamentals("AAPL")

    def av_json(statement):
        part = long[long.statement == statement]
        reps = []
        for d, g in part.groupby("fiscal_date"):
            rep = {"fiscalDateEnding": d.strftime("%Y-%m-%d")}
            rep.update({r.field: str(r.value) for r in g.itertuples()})
            reps.append(rep)
        return {"quarterlyReports": sorted(reps, key=lambda r: r["fiscalDateEnding"], reverse=True)}

    class R:
        def __init__(self, j): self.j = j
        def json(self): return self.j

    def fake_get(url, *a, **k):
        return R(av_json("income" if "INCOME" in url else "balance"))

    monkeypatch.setattr(legacy.utils.requests, "get", fake_get)
    # The original runs on pandas<3, where fillna("0") on a float column upcast to object;
    # emulate that so the unmodified legacy function can run here.
    orig_fillna = pd.DataFrame.fillna

    def old_fillna(self, value=None, *a, inplace=False, **k):
        if isinstance(value, str):
            res = orig_fillna(self.astype(object), value, *a, **k)
            if inplace:
                for c in self.columns:
                    self[c] = res[c]
                return None
            return res
        return orig_fillna(self, value, *a, inplace=inplace, **k)

    monkeypatch.setattr(pd.DataFrame, "fillna", old_fillna)
    old = legacy.utils.finstmt("AAPL")
    monkeypatch.setattr(pd.DataFrame, "fillna", orig_fillna)
    new = new_utils.finstmt("AAPL")
    num = ["revenue", "grosspnl", "netIncome", "assets", "liabilities", "equity", "cash"]
    assert list(new.columns) == list(old.columns)
    assert new["asof"].tolist() == old["asof"].tolist()
    np.testing.assert_allclose(pd.to_numeric(new[num].stack()).to_numpy(dtype=float),
                               pd.to_numeric(old[num].stack()).to_numpy(dtype=float))


# ------------------------------------------------------------ positions / pnl
TRADES = "B30D90P2@1.10,S30D95P2@2.40,S30D105C2@2.30,B30D110C2@1.00"


def _sim(mod_positions, mod_option, last=100.0, vol=0.30):
    class Scr:  # tposition.set reads mleg.screener.ctx.lastdt only
        ctx = types.SimpleNamespace(lastdt=20000101)
    mod_option.mleg.screener = Scr
    t = mod_positions.tposition('x', 'x', 'x', 'x')
    t.ticker, t.exprdt = "TEST", mod_option.reldate(30)
    t.set(last, vol, mod_option.reldate(0))
    t.simtrades(TRADES)
    return t


def test_positions_pnl_and_greeks_match_legacy(legacy):
    a = _sim(new_positions, new_option)
    b = _sim(legacy.positions, legacy.option)
    assert a.dpnl() == b.dpnl()
    pd.testing.assert_frame_equal(a.pnlbylegs(), b.pnlbylegs())
    for g in ("delta", "gamma", "theta"):
        assert a.greek(g) == b.greek(g)
        pd.testing.assert_frame_equal(a.greekdf(g), b.greekdf(g))
    assert a.margin() == b.margin()
    assert a.getsimtrades() == b.getsimtrades()
    fa, fb = a.plot("pnl", None, 5), b.plot("pnl", None, 5)
    for ta, tb in zip(fa.data, fb.data):
        np.testing.assert_allclose(np.asarray(ta.y, dtype=float), np.asarray(tb.y, dtype=float))


def test_iron_condor_pnl_by_hand():
    a = _sim(new_positions, new_option)
    openvalue, realized, opencost, traded, openqty = a.dpnl()
    # opened: buy 90P@1.10, sell 95P@2.40, sell 105C@2.30, buy 110C@1.00, 2 contracts each
    assert opencost == pytest.approx(2 * (1.10 - 2.40 - 2.30 + 1.00))
    assert realized == 0 and traded == 16
    # a short condor is short vega/premium: value is a net credit (negative)
    assert openvalue < 0


def test_mleg_pricer_matches_legacy(legacy, monkeypatch):
    """opricer/legdf/dte2opx on the same metric series (tsmetricsdata patched in both)."""
    ss_new = new_myutils.stock_screener(FakeCtx(new_svc), False)
    ss_old = legacy.myutils.stock_screener(FakeCtx(legacy.svcreader), False)

    def ts(ticks, metrics, timespan=0):
        df = FX.metrics_batch(ticks.split(","), metrics.split(","))
        df["key"] = df["ticker"] + "/" + df["metric"]
        w = df.pivot_table(index="asof", columns="key", values="value").reset_index()
        w.columns.name = None
        return w

    monkeypatch.setattr(new_option, "tsmetricsdata", ts)
    monkeypatch.setattr(legacy.option, "tsmetricsdata", ts)
    for st in ("peg_condor", "bull_credit", "vol_straddle"):
        new_option.mleg.screener = ss_new
        o1 = ss_new("opricer", "AAPL", st, "", new_option.ovar(100.0, 0.3, 45))
        o1.set(100.0, 0.3)
        legacy.option.mleg.screener = ss_old
        o2 = ss_old("opricer", "AAPL", st, "", legacy.option.ovar(100.0, 0.3, 45))
        o2.set(100.0, 0.3)
        pd.testing.assert_frame_equal(o1.legdf(), o2.legdf())
        pd.testing.assert_frame_equal(o1.dte2opx(), o2.dte2opx())


def test_ipython_shell_startup(live, monkeypatch):
    """``from mystock_ui.legacy.shell import *`` reproduces the legacy myglobal/setup.py session."""
    monkeypatch.setenv("MYSTOCK_SHELL_LOGIN", "demo")
    monkeypatch.setenv("MYSTOCK_SHELL_PASSWORD", "demo")
    for m in ("mystock_ui.legacy.shell", "mystock_ui.legacy.myglobal"):
        sys.modules.pop(m, None)
    shell = importlib.import_module("mystock_ui.legacy.shell")
    assert shell.mleg.screener is shell.ss and shell.ss.ctx is shell.ctx
    assert shell.q.aapl == "AAPL" and shell.feat.pe == "pe" and shell.ssf.option.opricer == "opricer"
    assert "movers" in dir(shell.ssq)
    assert shell.sel.keys() == ["aapl0"] and len(shell.session) == 32
    assert shell.ss("movers", -1.0, "weekpnl", 1.0, None) is not None
    import atexit
    atexit.unregister(shell.teardown_my_shell)
