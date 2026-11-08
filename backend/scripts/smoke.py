#!/usr/bin/env python3
"""Smoke test for every op in docs/PROTOCOL.md.  Usage: python3 -I smoke.py HOST PORT API_KEY

Checks status, column names/order against the spec and Arrow types (no DECIMAL). Designed for a server
started on a `seed-demo` database without third-party API keys (UPSTREAM is accepted where the op needs
the network and there is no cached data).
"""
import io
import json
import socket
import struct
import sys
import time

import pyarrow as pa
import pyarrow.ipc as ipc

STOCK_COLS = ("asof, ticker, name, sect, ind, mktsize, symid, mktcap, pe, eps, dividend, shares, ttmearnings, ttmbookvalue, "
              "ttmrevenue, ttmgrossprofit, daypnl, weekpnl, mompnl, qoqpnl, yoypnl, pnldir, cagr, corr, upcorr, downcorr, coskew, "
              "range, rangescore, lastdevz, seasonality, seasonindex, xadv, avgdr, momtr, px2bbpct, volratio, signal, aroonup, "
              "aroondwn, hangtime, peak30, drop30, qpeak, prscore, qdrop, qrebound, precovpx, decovpx, kurt, trisktouch, "
              "triskbreach, triskvol, qdropavg, volratioyy, maxpctchg, sharpness, volscore, histvol, optvol, optivol, rangevol, "
              "rsi, weekdip, momdip, bumpiness, r2, maxdrs, mindrs, stlpm, dtpm, ema20, ema60, ema200, beta, alpha, lastpx, "
              "mktrscore, mktdip, mktsindex, summ, impvol, earnings, income, revenue").split(", ")
PORT_COLS = ("asof, name, daypnl, weekpnl, mompnl, qoqpnl, yoypnl, roi, corr, upcorr, downcorr, coskew, seasonality, seasonindex, "
             "aroonup, aroondwn, hangtime, peak30, drop30, px2bbpct, qpeak, momtr, rangescore, prscore, qdrop, qrebound, range, "
             "lastdevz, optvol, optivol, kurt, trisktouch, triskbreach, rsi, weekdip, momdip, bumpiness, r2, histvol, stlpm, dtpm, "
             "ema20, ema60, ema200, beta, alpha, mktrscore, mktdip, mktsindex, wgtype").split(", ")
CHAIN_COLS = ("contract_id, symbol, type, asof, expiration, strike, last, mark, bid, bid_size, ask, ask_size, volume, "
              "open_interest, implied_volatility, delta, gamma, theta, vega, rho, upx").split(", ")
SHADOW_COLS = ["ymd"] + "upx, vol, risk, days, lput, sput, scall, lcall, netpx, netdelta, pnl, excesspnl, drift, weekpnl".split(", ")
METRICS = ("OPEN,HIGH,LOW,PREV,MKTPX,RSI,STDMKTPX,ROI,DAYPNL,WEEKPNL,MOMPNL,QOQPNL,YOYPNL,DTPM,STLPM,OPTVOL,OPTIVOL,EMA20,"
           "EMA60,EMA200,RANGESCORE,LASTDEVZ,CORR,BUMPINESS,PRSCORE,KURT,CCI,CCIQ,COSKEW,PCTCHG,ATR,ADV,TRMON,PNLDIR,MOMDIP").split(",")


class Client:
    def __init__(self, host, port, key):
        self.key = key
        self.sock = socket.create_connection((host, port), timeout=600)
        self.f = self.sock.makefile("rb")
        self.n = 0

    def _frame(self):
        hdr = self.f.read(4)
        if len(hdr) < 4:
            raise EOFError("connection closed")
        (n,) = struct.unpack(">I", hdr)
        return self.f.read(n)

    def call(self, op, key=None, **args):
        self.n += 1
        req = {"v": 1, "id": "s%04d" % self.n, "key": self.key if key is None else key, "op": op, "args": args}
        b = json.dumps(req).encode()
        self.sock.sendall(struct.pack(">I", len(b)) + b)
        head = json.loads(self._frame())
        assert head["id"] == req["id"], head
        if head["status"] != "ok":
            return head, None
        fmt = head["format"]
        if fmt == "json":
            return head, json.loads(self._frame())
        if fmt == "arrow":
            return head, ipc.open_stream(self.f).read_all()
        return head, None


results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else "  -> " + str(detail)[:300]))


def no_decimal(t):
    return all(not pa.types.is_decimal(f.type) for f in t.schema)


def arrow_ok(name, head, t, cols=None, min_rows=0):
    if head["status"] != "ok":
        check(name, False, head)
        return False
    ok = t is not None and no_decimal(t) and t.num_rows >= min_rows
    if cols is not None:
        ok = ok and t.column_names == cols
    check(name + " (%d rows)" % (t.num_rows if t is not None else -1), ok,
          {"cols": t.column_names if t is not None else None, "expected": cols, "types": str(t.schema) if t is not None else None})
    return ok


def main():
    host, port, key = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    c = Client(host, port, key)

    h, j = c.call("ping")
    check("ping", h["status"] == "ok" and j["pong"] is True and j["db_date"] and "version" in j, j)
    db_date = j["db_date"]

    h, j = c.call("admin.status")
    check("admin.status", h["status"] == "ok" and set(j) >= {"db_date", "symbols", "jobs"} and j["symbols"] > 50, j)
    if h["status"] == "ok" and j["jobs"]:
        check("admin.status job keys", set(j["jobs"][0]) == {"job", "status", "started_at", "finished_at", "message"}, j["jobs"][0])

    h, j = c.call("admin.run_job", job="NOPE")
    check("admin.run_job unknown -> BAD_REQUEST", h.get("code") == "BAD_REQUEST", h)
    h, j = c.call("admin.run_job", job="OPTFEAT")
    check("admin.run_job OPTFEAT accepted", h["status"] == "ok" and j == {"accepted": True}, (h, j))

    h, j = c.call("admin.reload")
    check("admin.reload", h["status"] == "ok" and j == {"reloaded": True}, (h, j))

    h, t = c.call("screener.stocks")
    if arrow_ok("screener.stocks", h, t, STOCK_COLS, 40):
        check("screener.stocks types", t.schema.field("asof").type == pa.date32() and t.schema.field("symid").type == pa.int32()
              and t.schema.field("mktcap").type == pa.float64(), str(t.schema)[:400])
        df = t.to_pandas()
        check("screener.stocks AAPL present, mktcap raw $", (df.ticker == "AAPL").any() and df[df.ticker == "AAPL"].mktcap.iloc[0] > 1e11)
        check("screener.stocks summ/income encoding", df.summ.str.contains("[ud]").any() and df.income.str.match(r"^[01]+$").all(),
              df[["summ", "income"]].head(3).to_dict())
    h, t = c.call("screener.stocks", limit=5)
    arrow_ok("screener.stocks limit=5", h, t, STOCK_COLS)
    check("screener.stocks limit honoured", t is not None and t.num_rows == 5)

    h, t = c.call("screener.portfolios")
    arrow_ok("screener.portfolios", h, t, PORT_COLS, 10)
    h, t = c.call("screener.portfolios", wgtype="MW")
    if arrow_ok("screener.portfolios wgtype=MW", h, t, PORT_COLS, 5):
        check("screener.portfolios MW only", set(t.column("wgtype").to_pylist()) == {"MW"} and "TECH/MW" in t.column("name").to_pylist())

    h, t = c.call("prices.history", ticker="aapl")
    arrow_ok("prices.history", h, t, ["asof", "open", "high", "low", "close", "adjclose", "volume"], 2000)
    h, t = c.call("prices.history", ticker="ZZZZ")
    check("prices.history unknown -> NOT_FOUND", h.get("code") == "NOT_FOUND", h)

    h, t = c.call("prices.ohlc", ticker="AAPL")
    if arrow_ok("prices.ohlc", h, t, ["asof", "open", "high", "low", "close", "prev", "volume"], 2000):
        df = t.to_pandas()
        check("prices.ohlc adjusted (no split jump) and prev = previous close",
              abs(df.close.pct_change().dropna()).max() < 0.5 and abs(df.prev.iloc[-1] - df.close.iloc[-2]) < 1e-6,
              df.tail(3).to_dict())
    h, t = c.call("prices.ohlc", ticker="AAPL", start="2024-01-01", end="20240131")
    arrow_ok("prices.ohlc start/end (ISO + yyyymmdd)", h, t, ["asof", "open", "high", "low", "close", "prev", "volume"], 15)

    for fld in ["open", "high", "low", "close", "prev", "volume"]:
        h, t = c.call("prices.asof", date="2024-06-01", field=fld, tickers=["MSFT", "AAPL", "NOPE"])
        if arrow_ok("prices.asof %s" % fld, h, t, ["ticker", "value"], 3):
            v = t.to_pydict()
            check("prices.asof %s order + missing=0" % fld, v["ticker"] == ["MSFT", "AAPL", "NOPE"] and v["value"][2] == 0.0 and v["value"][0] > 0, v)

    for kind, name in [("STK", "AAPL"), ("EWT", "TECH"), ("MWT", "MKT"), ("AWT", "MKT")]:
        h, t = c.call("prices.series", ticker=name, kind=kind)
        if arrow_ok("prices.series %s %s" % (kind, name), h, t, ["asof", "value"], 500):
            if kind != "AWT":
                check("prices.series %s last=100" % kind, abs(t.column("value")[-1].as_py() - 100) < 1e-6, t.column("value")[-1])
    h, t = c.call("prices.series", ticker="NOPORT", kind="EWT")
    check("prices.series unknown portfolio -> NOT_FOUND", h.get("code") == "NOT_FOUND", h)

    empty_ok = {"OPEN", "HIGH", "LOW", "PREV", "STDMKTPX"}
    for m in METRICS:
        t0 = time.time()
        h, t = c.call("metrics.series", ticker="SPY", metric=m)
        ok = arrow_ok("metrics.series SPY %s [%.1fs]" % (m, time.time() - t0), h, t, ["asof", "value"])
        if ok and m not in empty_ok:
            check("metrics.series %s non-empty" % m, t.num_rows > 100, t.num_rows)
    h, t = c.call("metrics.series", ticker="SPY", metric="BOGUS")
    check("metrics.series unknown metric -> BAD_REQUEST", h.get("code") == "BAD_REQUEST", h)

    h, t = c.call("metrics.batch", tickers=["SPY", "QQQ"], metrics=["RSI", "DAYPNL"], since="2025-01-01")
    if arrow_ok("metrics.batch", h, t, ["ticker", "metric", "asof", "value"], 100):
        df = t.to_pandas()
        check("metrics.batch since + pairs", set(zip(df.ticker, df.metric)) == {("SPY", "RSI"), ("SPY", "DAYPNL"), ("QQQ", "RSI"), ("QQQ", "DAYPNL")}
              and str(df["asof"].min())[:10] >= "2025-01-01")

    h, j = c.call("quote.live", ticker="AAPL")
    check("quote.live", h["status"] == "ok" and list(j) == ["ticker", "spot_price", "spot_vol", "next_earnings"] and j["spot_price"] > 0
          and j["spot_vol"] > 0, j)

    h, t = c.call("market.vix", days=30)
    if h["status"] == "ok":
        arrow_ok("market.vix", h, t, ["date", "vix", "skew"])
    else:
        check("market.vix UPSTREAM acceptable offline", h.get("code") == "UPSTREAM", h)

    h, t = c.call("options.chain", ticker="AAPL")
    if arrow_ok("options.chain (stale snapshot when offline)", h, t, CHAIN_COLS, 50):
        check("options.chain types C/P decimals", set(t.column("type").to_pylist()) <= {"C", "P"} and max(t.column("implied_volatility").to_pylist()) < 5)
    h, t = c.call("options.chain", ticker="MSFT")
    check("options.chain no snapshot -> ok or UPSTREAM", h["status"] == "ok" or h.get("code") == "UPSTREAM", h)

    h, t = c.call("fundamentals.statements", ticker="AAPL")
    if arrow_ok("fundamentals.statements", h, t, ["statement", "fiscal_date", "period", "field", "value"], 50):
        check("fundamentals statements/periods", set(t.column("statement").to_pylist()) == {"income", "balance"}
              and set(t.column("period").to_pylist()) == {"annual", "quarterly"})

    h, t = c.call("news.list", ticker="AAPL", limit=5)
    if h["status"] == "ok":
        arrow_ok("news.list", h, t, ["published", "author", "url", "title", "sentiment", "sentiment_reasoning"])
    else:
        check("news.list UPSTREAM without POLYGON_API_KEY", h.get("code") == "UPSTREAM", h)

    h, j = c.call("auth.login", login="demo", password="wrong")
    check("auth.login bad password -> UNAUTHORIZED", h.get("code") == "UNAUTHORIZED", h)
    h, j = c.call("auth.login", login="demo", password="demo")
    check("auth.login", h["status"] == "ok" and list(j) == ["token", "login", "expires_at"] and len(j["token"]) == 32, j)
    tok = j["token"]
    h, j2 = c.call("auth.login", login="demo", password="demo")
    check("auth.login reuses session", j2 and j2["token"] == tok, j2)

    h, t = c.call("trades.list", token="bad")
    check("trades.list bad token -> FORBIDDEN", h.get("code") == "FORBIDDEN", h)
    h, j = c.call("trades.book", token=tok, portfolio="p1", position="aaplic", buysell="S", legsym="AAPL@200@261218@S1C", px=2.5, qty=-1)
    check("trades.book (live upx/uvol)", h["status"] == "ok" and isinstance(j.get("trade_id"), int), (h, j))
    h, j = c.call("trades.book", token=tok, portfolio="p1", position="aaplic", buysell="B", legsym="AAPL@180@261218@B1P", px=1.5, qty=1,
                  upx=190.0, uvol=0.3, tradedt="2026-11-02")
    check("trades.book explicit upx/uvol/tradedt", h["status"] == "ok", (h, j))
    h, t = c.call("trades.list", token=tok)
    cols = ["trade_id", "login", "portfolio", "position", "buysell", "legsym", "px", "qty", "tradedt", "upx", "uvol"]
    if arrow_ok("trades.list", h, t, cols, 2):
        check("trades.list tradedt is date", t.schema.field("tradedt").type == pa.date32())
    h, j = c.call("trades.book", token=tok, portfolio="p1", position="aaplic", buysell="S", legsym="AAPL@200@261218@S1C", px=0, qty=0)
    h, t = c.call("trades.list", token=tok)
    check("trades.book qty=0 deletes leg", h["status"] == "ok" and "AAPL@200@261218@S1C" not in t.column("legsym").to_pylist())
    # remove the remaining test leg so repeated smoke runs leave the user's book unchanged
    c.call("trades.book", token=tok, portfolio="p1", position="aaplic", buysell="B", legsym="AAPL@180@261218@B1P", px=0, qty=0)

    h, t = c.call("config.queries")
    arrow_ok("config.queries", h, t, ["name", "value"], 3)
    h, t = c.call("config.reports")
    arrow_ok("config.reports", h, t, ["function", "report", "params"], 3)
    h, j = c.call("predictions.add", ticker="NVDA", tgtpx=200.0, mktpx=180.0)
    check("predictions.add", h["status"] == "ok" and j == {"ok": True}, (h, j))
    h, t = c.call("predictions.list")
    if arrow_ok("predictions.list", h, t, ["ticker", "asof", "tgtpx", "open", "high", "low", "close"], 3):
        check("predictions.list has NVDA", "NVDA" in t.column("ticker").to_pylist())

    initdt = "2026-09-01"
    h, t = c.call("backtest.shadow", ticker="SPY", initdt=initdt)
    if arrow_ok("backtest.shadow", h, t, SHADOW_COLS, 10):
        check("backtest.shadow types", t.schema.field("ymd").type == pa.date32() and all(t.schema.field(n).type == pa.float64() for n in SHADOW_COLS[1:])
              and str(t.column("ymd")[0].as_py()) == initdt)
    h, t = c.call("backtest.shadow", ticker="SPY", initdt=initdt, optvol=0.2, riskvol=0.3)
    arrow_ok("backtest.shadow explicit vols", h, t, SHADOW_COLS, 10)

    h, j = c.call("auth.logout", token=tok)
    check("auth.logout", h["status"] == "ok" and j == {"ok": True})
    h, t = c.call("trades.list", token=tok)
    check("trades.list after logout -> FORBIDDEN", h.get("code") == "FORBIDDEN", h)

    h, j = c.call("nope.op")
    check("unknown op -> BAD_REQUEST", h.get("code") == "BAD_REQUEST", h)
    h, j = c.call("ping", key="wrong")
    check("bad key -> UNAUTHORIZED", h.get("code") == "UNAUTHORIZED", h)
    try:
        c.call("ping")
        check("connection closed after UNAUTHORIZED", False)
    except (EOFError, ConnectionError, OSError, AssertionError, ValueError, struct.error):
        check("connection closed after UNAUTHORIZED", True)

    failed = [r for r in results if not r[1]]
    print("\n%d checks, %d failed" % (len(results), len(failed)))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
