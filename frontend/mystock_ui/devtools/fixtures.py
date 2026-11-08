"""Deterministic fixture data shaped exactly like the PROTOCOL.md responses.

Used by the fake backend (``scripts/fake_backend.py``) and the tests. Synthetic numbers only.
"""
from __future__ import annotations

import datetime as dt
import math
import secrets

import numpy as np
import pandas as pd
from scipy.stats import norm

STOCK_COLUMNS = (
    "asof, ticker, name, sect, ind, mktsize, symid, mktcap, pe, eps, dividend, shares, ttmearnings, ttmbookvalue, "
    "ttmrevenue, ttmgrossprofit, daypnl, weekpnl, mompnl, qoqpnl, yoypnl, pnldir, cagr, corr, upcorr, downcorr, "
    "coskew, range, rangescore, lastdevz, seasonality, seasonindex, xadv, avgdr, momtr, px2bbpct, volratio, signal, "
    "aroonup, aroondwn, hangtime, peak30, drop30, qpeak, prscore, qdrop, qrebound, precovpx, decovpx, kurt, "
    "trisktouch, triskbreach, triskvol, qdropavg, volratioyy, maxpctchg, sharpness, volscore, histvol, optvol, "
    "optivol, rangevol, rsi, weekdip, momdip, bumpiness, r2, maxdrs, mindrs, stlpm, dtpm, ema20, ema60, ema200, "
    "beta, alpha, lastpx, mktrscore, mktdip, mktsindex, summ, impvol, earnings, income, revenue"
).split(", ")
PORT_COLUMNS = (
    "asof, name, daypnl, weekpnl, mompnl, qoqpnl, yoypnl, roi, corr, upcorr, downcorr, coskew, seasonality, "
    "seasonindex, aroonup, aroondwn, hangtime, peak30, drop30, px2bbpct, qpeak, momtr, rangescore, prscore, qdrop, "
    "qrebound, range, lastdevz, optvol, optivol, kurt, trisktouch, triskbreach, rsi, weekdip, momdip, bumpiness, r2, "
    "histvol, stlpm, dtpm, ema20, ema60, ema200, beta, alpha, mktrscore, mktdip, mktsindex, wgtype"
).split(", ")
SHADOW_COLUMNS = ["ymd", "upx", "vol", "risk", "days", "lput", "sput", "scall", "lcall", "netpx", "netdelta",
                  "pnl", "excesspnl", "drift", "weekpnl"]
OPTION_COLUMNS = ["contract_id", "symbol", "type", "asof", "expiration", "strike", "last", "mark", "bid", "bid_size",
                  "ask", "ask_size", "volume", "open_interest", "implied_volatility", "delta", "gamma", "theta",
                  "vega", "rho", "upx"]

ETFS = {"SPY": "MKT", "QQQ": "MKT", "IWM": "MKT", "XLK": "SECT", "XLF": "SECT", "GLD": "COMM"}
STOCKS = {
    "AAPL": ("TECH", "COMPUTERS"), "MSFT": ("TECH", "SOFTWARE"), "GOOG": ("TECH", "INTERNET"),
    "AMZN": ("TRADE", "RETAIL"), "NVDA": ("TECH", "SEMIS"), "META": ("TECH", "INTERNET"),
    "AMD": ("TECH", "SEMIS"), "INTC": ("TECH", "SEMIS"), "IBM": ("TECH", "SOFTWARE"),
    "ORCL": ("TECH", "SOFTWARE"), "JPM": ("FINANCE", "BANKS"), "BAC": ("FINANCE", "BANKS"),
    "GS": ("FINANCE", "BANKS"), "XOM": ("ENERGY", "OIL"), "CVX": ("ENERGY", "OIL"), "PFE": ("HEALTH", "DRUGS"),
    "MRK": ("HEALTH", "DRUGS"), "UNH": ("HEALTH", "INSURANCE"), "WMT": ("TRADE", "RETAIL"),
    "TGT": ("TRADE", "RETAIL"), "COST": ("TRADE", "RETAIL"), "KO": ("MANUFACTURING", "BEVERAGES"),
    "PG": ("MANUFACTURING", "HOUSEHOLD"), "O": ("REIT", "REIT"),
}
TICKERS = list(ETFS) + list(STOCKS)
PORTFOLIOS = ["MKT", "TECH", "FINANCE", "HEALTH", "ENERGY", "TRADE", "MANUFACTURING", "REIT", "FANG"]


def _seed(name: str) -> int:
    return sum(ord(c) * (i + 7) for i, c in enumerate(name)) % (2**31)


class Fixtures:
    def __init__(self, today: dt.date | None = None):
        self.today = today or dt.date.today()
        self.days = pd.bdate_range(end=pd.Timestamp(self.today) - pd.Timedelta(days=1), periods=900)
        self.db_date = self.days[-1].date()
        self._hist: dict[str, pd.DataFrame] = {}
        self.users = {"demo": "demo"}
        self.sessions: dict[str, str] = {}
        self.trades: list[dict] = []
        self._next_trade = 1
        self.predictions = pd.DataFrame({
            "ticker": ["AAPL", "MSFT", "AAPL"],
            "asof": [self.days[-30], self.days[-20], self.days[-5]],
            "tgtpx": [200.0, 420.0, 210.0], "open": [190.0, 400.0, 205.0], "high": [190.0, 400.0, 205.0],
            "low": [190.0, 400.0, 205.0], "close": [190.0, 400.0, 205.0],
        })
        self._seed_trades()

    # ------------------------------------------------------------- price paths
    def history(self, ticker: str) -> pd.DataFrame:
        t = ticker.upper()
        if t not in self._hist:
            rng = np.random.default_rng(_seed(t))
            n = len(self.days)
            vol = 0.15 + (_seed(t) % 40) / 100.0
            rets = rng.normal(0.0003, vol / math.sqrt(252), n)
            close = (30 + _seed(t) % 400) * np.exp(np.cumsum(rets))
            close = close / close[-1] * (30 + _seed(t) % 400)
            opn = close * (1 + rng.normal(0, 0.004, n))
            high = np.maximum(opn, close) * (1 + np.abs(rng.normal(0, 0.006, n)))
            low = np.minimum(opn, close) * (1 - np.abs(rng.normal(0, 0.006, n)))
            adj = close * np.linspace(0.97, 1.0, n)
            vol_ = rng.integers(1_000_000, 50_000_000, n).astype("int64")
            self._hist[t] = pd.DataFrame({"asof": self.days, "open": opn, "high": high, "low": low, "close": close,
                                          "adjclose": adj, "volume": vol_})
        return self._hist[t].copy()

    def known(self, ticker: str) -> bool:
        return ticker.upper() in TICKERS

    # ---------------------------------------------------------------- screener
    def screener_stocks(self) -> pd.DataFrame:
        rows = []
        for i, t in enumerate(TICKERS):
            h = self.history(t)
            rng = np.random.default_rng(_seed(t) + 1)
            px = h.close.to_numpy()
            last = float(px[-1])
            dr = np.diff(np.log(px))
            histvol = float(np.std(dr[-100:]) * math.sqrt(252) * 100)
            optvol = float(np.std(dr[-21:]) * math.sqrt(252) * 100)
            etf = t in ETFS
            sect, ind = ("NONE", "ETF") if etf else STOCKS[t]
            eps = float(rng.uniform(-2, 15))
            row = {c: float(rng.normal(0, 1)) for c in STOCK_COLUMNS}
            row.update({
                "asof": self.db_date, "ticker": t, "name": f"{t} Inc" if not etf else f"{t} ETF", "sect": sect.lower(),
                "ind": ind.lower(), "mktsize": "", "symid": float(i + 1),
                "mktcap": 0.0 if etf else float(rng.uniform(5e9, 3e12)), "pe": 0.0, "eps": eps,
                "dividend": float(rng.uniform(0, 4)), "shares": float(rng.uniform(1e8, 1e10)),
                "daypnl": 100 * (px[-1] / px[-2] - 1), "weekpnl": 100 * (px[-1] / px[-6] - 1),
                "mompnl": 100 * (px[-1] / px[-22] - 1), "qoqpnl": 100 * (px[-1] / px[-64] - 1),
                "yoypnl": 100 * (px[-1] / px[-253] - 1), "cagr": float(rng.uniform(-20, 40)),
                "corr": float(rng.uniform(-0.2, 1.0)), "downcorr": float(rng.uniform(-0.2, 1.0)),
                "range": float(rng.uniform(10, 80)), "rangescore": float(rng.uniform(0, 100)),
                "prscore": float(rng.uniform(0, 100)), "momtr": float(rng.uniform(0, 100)),
                "px2bbpct": float(rng.uniform(0, 100)), "xadv": float(rng.uniform(0.5, 2)),
                "volratio": float(rng.uniform(0.5, 2)), "qpeak": float(rng.uniform(0, 40)),
                "qdrop": float(rng.uniform(0, 40)), "qrebound": float(rng.uniform(0, 30)),
                "qdropavg": float(rng.uniform(5, 25)), "trisktouch": float(rng.uniform(0, 20)),
                "triskbreach": float(rng.uniform(0, 10)), "triskvol": histvol + float(rng.uniform(-5, 10)),
                "histvol": histvol, "optvol": optvol, "optivol": optvol + float(rng.uniform(-3, 3)),
                "rsi": float(rng.uniform(10, 90)), "bumpiness": float(rng.uniform(1, 20)),
                "r2": float(rng.uniform(0, 1)), "stlpm": float(rng.uniform(0, 5)), "dtpm": float(rng.uniform(5, 15)),
                "ema20": float(rng.uniform(90, 110)), "ema60": float(rng.uniform(90, 110)),
                "ema200": float(rng.uniform(85, 115)), "lastpx": last, "impvol": histvol + float(rng.uniform(-5, 15)),
                "earnings": float(rng.integers(0, 365)), "summ": "".join(rng.choice(list("ud"), 8)),
                "income": str(int(rng.integers(0, 2))), "revenue": str(int(rng.integers(0, 2))),
            })
            rows.append(row)
        df = pd.DataFrame(rows)[STOCK_COLUMNS]
        df["asof"] = pd.to_datetime(df["asof"])
        return df

    def screener_portfolios(self, wgtype=None) -> pd.DataFrame:
        rows = []
        for p in PORTFOLIOS:
            for w in ("EW", "MW"):
                rng = np.random.default_rng(_seed(p + w))
                row = {c: float(rng.normal(0, 5)) for c in PORT_COLUMNS}
                row.update({"asof": self.db_date, "name": f"{p}/{w}", "wgtype": w, "qdrop": float(rng.uniform(0, 30)),
                            "qrebound": float(rng.uniform(0, 30)), "ema20": float(rng.uniform(90, 110)),
                            "ema60": float(rng.uniform(90, 110)), "histvol": float(rng.uniform(10, 40))})
                rows.append(row)
        df = pd.DataFrame(rows)[PORT_COLUMNS]
        df["asof"] = pd.to_datetime(df["asof"])
        if wgtype:
            df = df[df.wgtype == wgtype.upper()].reset_index(drop=True)
        return df

    # ------------------------------------------------------------------ prices
    def prices_ohlc(self, ticker, start=None, end=None) -> pd.DataFrame:
        h = self.history(ticker)
        f = h.adjclose / h.close
        df = pd.DataFrame({"asof": h["asof"], "open": h.open * f, "high": h.high * f, "low": h.low * f,
                           "close": h.adjclose, "prev": h.adjclose.shift(1).bfill(), "volume": h.volume})
        if start:
            df = df[df["asof"] >= pd.Timestamp(start)]
        if end:
            df = df[df["asof"] <= pd.Timestamp(end)]
        return df.reset_index(drop=True)

    def prices_asof(self, date, field, tickers) -> pd.DataFrame:
        vals = []
        for t in tickers:
            df = self.prices_ohlc(t, start=date) if self.known(t) else pd.DataFrame()
            vals.append(float(df[field].iloc[0]) if len(df) else 0.0)
        return pd.DataFrame({"ticker": [t.upper() for t in tickers], "value": vals})

    def prices_series(self, ticker, kind) -> pd.DataFrame:
        base = ticker.upper() if self.known(ticker) else "SPY"
        h = self.history(base)
        cal = pd.date_range(h["asof"].iloc[0], h["asof"].iloc[-1], freq="D")
        s = h.set_index("asof").adjclose.reindex(cal).ffill()
        if kind.upper() == "AWT":
            s = s * (10 + _seed(ticker) % 50)
        else:
            s = 100.0 * s / s.iloc[-1]
        return pd.DataFrame({"asof": cal, "value": s.to_numpy()})

    # ----------------------------------------------------------------- metrics
    def metric(self, ticker, metric) -> pd.DataFrame:
        h = self.history(ticker)
        px = 100.0 * h.adjclose / h.adjclose.iloc[-1]
        dr = np.log(px).diff()
        m = metric.upper()
        if m in ("MKTPX", "STDMKTPX"):
            v = px
        elif m == "OPTVOL":
            v = dr.rolling(21).std() * math.sqrt(252) * 100
        elif m == "OPTIVOL":
            v = dr.ewm(span=21).std() * math.sqrt(252) * 100
        elif m.startswith("EMA"):
            v = px.ewm(span=int(m[3:])).mean()
        elif m == "RSI":
            up = dr.clip(lower=0).rolling(14).mean()
            dn = (-dr.clip(upper=0)).rolling(14).mean()
            v = 100 - 100 / (1 + up / dn)
        elif m in ("OPEN", "HIGH", "LOW", "PREV"):
            v = h[m.lower() if m != "PREV" else "close"].shift(1 if m == "PREV" else 0)
        elif m.endswith("PNL"):
            span = {"DAYPNL": 1, "WEEKPNL": 5, "MOMPNL": 21, "QOQPNL": 63, "YOYPNL": 252}.get(m, 5)
            v = 100 * (px / px.shift(span) - 1)
        else:
            rng = np.random.default_rng(_seed(ticker + m))
            v = pd.Series(np.cumsum(rng.normal(0, 1, len(px))))
        return pd.DataFrame({"asof": h["asof"], "value": v.to_numpy(dtype=float)}).dropna().reset_index(drop=True)

    def metrics_batch(self, tickers, metrics, since=None) -> pd.DataFrame:
        parts = []
        for t in tickers:
            for m in metrics:
                df = self.metric(t, m)
                if since:
                    df = df[df["asof"] >= pd.Timestamp(since)]
                df.insert(0, "metric", m.upper())
                df.insert(0, "ticker", t.upper())
                parts.append(df)
        if not parts:
            return pd.DataFrame({"ticker": [], "metric": [], "asof": pd.Series(dtype="datetime64[ns]"), "value": []})
        return pd.concat(parts, ignore_index=True)[["ticker", "metric", "asof", "value"]]

    # -------------------------------------------------------------------- live
    def quote_live(self, ticker) -> dict:
        h = self.history(ticker)
        iv = float(self.screener_stocks().set_index("ticker").loc[ticker.upper(), "impvol"])
        return {"ticker": ticker.upper(), "spot_price": round(float(h.close.iloc[-1]) * 1.001, 2),
                "spot_vol": round(iv, 2), "next_earnings": (self.today + dt.timedelta(days=40)).isoformat()}

    def market_vix(self, days=None) -> pd.DataFrame:
        rng = np.random.default_rng(42)
        d = self.days[-400:]
        df = pd.DataFrame({"date": d, "vix": 15 + np.abs(np.cumsum(rng.normal(0, 0.5, len(d)))) % 20,
                           "skew": 13 + rng.normal(0, 0.5, len(d))})
        return df.tail(days).reset_index(drop=True) if days else df

    def options_chain(self, ticker) -> pd.DataFrame:
        last = float(self.history(ticker).close.iloc[-1])
        iv = 0.30
        rows = []
        step = 5.0 if last > 50 else 1.0
        strikes = np.arange(math.floor(last * 0.7 / step) * step, last * 1.3, step)
        for days in (30, 60, 90, 180):
            exp = self.today + dt.timedelta(days=days)
            t = days / 365.0
            for k in strikes:
                d1 = (math.log(last / k) + 0.5 * iv * iv * t) / (iv * math.sqrt(t))
                d2 = d1 - iv * math.sqrt(t)
                for typ in ("C", "P"):
                    if typ == "C":
                        prc = last * norm.cdf(d1) - k * norm.cdf(d2)
                        delta = norm.cdf(d1)
                    else:
                        prc = k * norm.cdf(-d2) - last * norm.cdf(-d1)
                        delta = norm.cdf(d1) - 1
                    rows.append({
                        "contract_id": f"{ticker.upper()}{exp:%y%m%d}{typ}{int(k * 1000):08d}", "symbol": ticker.upper(),
                        "type": typ, "asof": pd.Timestamp(self.db_date), "expiration": pd.Timestamp(exp),
                        "strike": float(k), "last": prc, "mark": prc, "bid": prc * 0.98, "bid_size": 10.0,
                        "ask": prc * 1.02, "ask_size": 10.0, "volume": 100.0, "open_interest": float(1000 + int(k)),
                        "implied_volatility": iv + 0.0005 * abs(k - last), "delta": float(delta),
                        "gamma": float(norm.pdf(d1) / (last * iv * math.sqrt(t))), "theta": -0.05, "vega": 0.1,
                        "rho": 0.01, "upx": last,
                    })
        return pd.DataFrame(rows)[OPTION_COLUMNS]

    def fundamentals(self, ticker) -> pd.DataFrame:
        rows = []
        qs = pd.date_range(end=pd.Timestamp(self.today), periods=8, freq="QE")
        rng = np.random.default_rng(_seed(ticker) + 9)
        for q in qs:
            for field in ("totalRevenue", "grossProfit", "netIncome"):
                rows.append(("income", q, "quarterly", field, float(rng.uniform(1e9, 5e10))))
            for field in ("totalAssets", "totalLiabilities", "totalShareholderEquity", "cashAndShortTermInvestments"):
                rows.append(("balance", q, "quarterly", field, float(rng.uniform(1e10, 4e11))))
        df = pd.DataFrame(rows, columns=["statement", "fiscal_date", "period", "field", "value"])
        return df

    def news(self, ticker, limit=10) -> pd.DataFrame:
        n = min(3, limit or 10)
        return pd.DataFrame({
            "published": pd.to_datetime([self.today - dt.timedelta(days=i) for i in range(n)]),
            "author": [f"Reporter {i}" for i in range(n)],
            "url": [f"https://example.com/{ticker}/{i}" for i in range(n)],
            "title": [f"{ticker} headline {i}" for i in range(n)],
            "sentiment": ["positive", "neutral", "negative"][:n],
            "sentiment_reasoning": [f"Reasoning {i}" for i in range(n)],
        })

    # -------------------------------------------------------------- user data
    def login(self, login, password):
        if self.users.get(login) != password:
            return None
        for tok, user in self.sessions.items():
            if user == login:
                return tok
        tok = secrets.token_hex(16)
        self.sessions[tok] = login
        return tok

    def _seed_trades(self):
        last = float(self.history("AAPL").close.iloc[-1])
        expiry = self.today + dt.timedelta(days=40)
        yymmdd = expiry.strftime("%y%m%d")
        base = round(last / 5) * 5
        legs = [(base - 20, "B1P", 1.10), (base - 10, "S1P", 2.40), (base + 10, "S1C", 2.30), (base + 20, "B1C", 1.00)]
        for strike, leg, px in legs:
            self._add_trade("demo", "p0", "aapl0", leg[0], f"AAPL@{int(strike)}@{yymmdd}@{leg}", px, 2.0, last, 0.3,
                            self.today - dt.timedelta(days=5))

    def _add_trade(self, login, portfolio, position, buysell, legsym, px, qty, upx, uvol, tradedt):
        tid = self._next_trade
        self._next_trade += 1
        self.trades.append({"trade_id": tid, "login": login, "portfolio": portfolio, "position": position,
                            "buysell": buysell, "legsym": legsym, "px": float(px), "qty": float(qty),
                            "tradedt": pd.Timestamp(tradedt), "upx": float(upx), "uvol": float(uvol)})
        return tid

    def trades_list(self, login) -> pd.DataFrame:
        cols = ["trade_id", "login", "portfolio", "position", "buysell", "legsym", "px", "qty", "tradedt", "upx", "uvol"]
        df = pd.DataFrame([t for t in self.trades if t["login"] == login], columns=cols)
        df["trade_id"] = df["trade_id"].astype("int64")
        df["tradedt"] = pd.to_datetime(df["tradedt"])
        for c in ["px", "qty", "upx", "uvol"]:
            df[c] = df[c].astype(float)
        return df

    def trades_book(self, login, a):
        if float(a["qty"]) == 0:
            self.trades = [t for t in self.trades if not (t["login"] == login and t["portfolio"] == a["portfolio"]
                           and t["position"] == a["position"] and t["legsym"] == a["legsym"])]
            return 0
        upx, uvol = a.get("upx"), a.get("uvol")
        if upx is None:
            q = self.quote_live(a["legsym"].split("@")[0])
            upx, uvol = q["spot_price"], q["spot_vol"]
        return self._add_trade(login, a["portfolio"], a["position"], a["buysell"], a["legsym"], a["px"], a["qty"],
                               upx, uvol, a.get("tradedt") or self.today)

    def queries(self) -> pd.DataFrame:
        return pd.DataFrame({"name": ["Large tech", "Cheap", "Watch"],
                             "value": ["mktcap > 100 and sect == 'TECH'", "pe > 0 and pe < 20", "aapl,msft,goog"]})

    def reports(self) -> pd.DataFrame:
        return pd.DataFrame({
            "function": ["tsstock", "tsdrawdown", "mktcompare", "summary", "nosuchfunc"],
            "report": ["Price trend", "Drawdown", "Compare to market", "Sector summary", "Broken report"],
            "params": ["ticker=AAPL#month=12", "ticker=SPY#month=24", "ticker=AAPL", "", "ticker=SPY"],
        })

    def shadow(self, ticker, initdt) -> pd.DataFrame:
        h = self.history(ticker)
        h = h[h["asof"] >= pd.Timestamp(initdt)].head(45)
        n = len(h)
        rng = np.random.default_rng(7)
        df = pd.DataFrame({c: rng.normal(0, 1, n) for c in SHADOW_COLUMNS[1:]})
        df.insert(0, "ymd", h["asof"].dt.normalize().to_numpy())
        df["upx"] = h.close.to_numpy()
        return df
