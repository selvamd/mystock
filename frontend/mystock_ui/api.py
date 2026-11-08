"""Typed wrappers, one function per backend op (docs/PROTOCOL.md).

Arrow responses come back as pandas DataFrames (dates as ``datetime64[ns]``),
JSON responses as dicts. Errors raise :class:`mystock_ui.client.ApiError`.
"""
from __future__ import annotations

import datetime as _dt
from typing import Any, Iterable

import pandas as pd
import pyarrow as pa

from .client import BAD_RESPONSE, ApiError, get_client


def iso_date(d: Any) -> str | None:
    """Accept date/datetime/Timestamp, ``yyyymmdd`` int/str or ISO string -> ``YYYY-MM-DD``."""
    if d is None or d == "":
        return None
    if isinstance(d, (pd.Timestamp, _dt.datetime)):
        return d.strftime("%Y-%m-%d")
    if isinstance(d, _dt.date):
        return d.isoformat()
    s = str(d).strip()
    if len(s) == 8 and s.isdigit():
        return f"{s[0:4]}-{s[4:6]}-{s[6:8]}"
    return s[:10]


def table_to_df(table: pa.Table) -> pd.DataFrame:
    df = table.to_pandas(date_as_object=False)
    for c in df.columns:
        if pd.api.types.is_datetime64_any_dtype(df[c]) and getattr(df[c].dt, "tz", None) is None:
            df[c] = df[c].astype("datetime64[ns]")
    return df


def _call(op: str, args: dict | None = None):
    clean = {k: v for k, v in (args or {}).items() if v is not None}
    return get_client().call(op, clean)


def _arrow(op: str, args: dict | None = None) -> pd.DataFrame:
    r = _call(op, args)
    if r.format != "arrow":
        raise ApiError(BAD_RESPONSE, f"{op}: expected arrow body, got {r.format}")
    return table_to_df(r.body)


def _json(op: str, args: dict | None = None) -> Any:
    r = _call(op, args)
    if r.format != "json":
        raise ApiError(BAD_RESPONSE, f"{op}: expected json body, got {r.format}")
    return r.body


def _tickers(tickers: Iterable[str] | str) -> list[str]:
    if isinstance(tickers, str):
        tickers = tickers.split(",")
    return [t.strip() for t in tickers if t and t.strip()]


# --- health / admin -----------------------------------------------------------
def ping() -> dict:
    return _json("ping")


def admin_status() -> dict:
    return _json("admin.status")


def admin_run_job(job: str) -> dict:
    return _json("admin.run_job", {"job": job})


def admin_reload() -> dict:
    return _json("admin.reload")


# --- screener snapshots -------------------------------------------------------
def screener_stocks(limit: int | None = None) -> pd.DataFrame:
    return _arrow("screener.stocks", {"limit": limit})


def screener_portfolios(wgtype: str | None = None) -> pd.DataFrame:
    return _arrow("screener.portfolios", {"wgtype": wgtype})


# --- prices -------------------------------------------------------------------
def prices_history(ticker: str) -> pd.DataFrame:
    return _arrow("prices.history", {"ticker": ticker})


def prices_ohlc(ticker: str, start: Any = None, end: Any = None) -> pd.DataFrame:
    return _arrow("prices.ohlc", {"ticker": ticker, "start": iso_date(start), "end": iso_date(end)})


def prices_asof(date: Any, field: str, tickers: Iterable[str] | str) -> pd.DataFrame:
    return _arrow("prices.asof", {"date": iso_date(date), "field": field, "tickers": _tickers(tickers)})


def prices_series(ticker: str, kind: str) -> pd.DataFrame:
    return _arrow("prices.series", {"ticker": ticker, "kind": kind.upper()})


# --- metrics ------------------------------------------------------------------
def metrics_series(ticker: str, metric: str) -> pd.DataFrame:
    return _arrow("metrics.series", {"ticker": ticker, "metric": metric.upper()})


def metrics_batch(tickers: Iterable[str] | str, metrics: Iterable[str] | str, since: Any = None) -> pd.DataFrame:
    if isinstance(metrics, str):
        metrics = metrics.split(",")
    return _arrow(
        "metrics.batch",
        {"tickers": _tickers(tickers), "metrics": [m.strip().upper() for m in metrics if m.strip()], "since": iso_date(since)},
    )


# --- live market data ---------------------------------------------------------
def quote_live(ticker: str) -> dict:
    return _json("quote.live", {"ticker": ticker})


def market_vix(days: int | None = None) -> pd.DataFrame:
    return _arrow("market.vix", {"days": days})


def options_chain(ticker: str, refresh: bool | None = None) -> pd.DataFrame:
    return _arrow("options.chain", {"ticker": ticker, "refresh": refresh})


def fundamentals_statements(ticker: str) -> pd.DataFrame:
    return _arrow("fundamentals.statements", {"ticker": ticker})


def news_list(ticker: str, limit: int | None = None) -> pd.DataFrame:
    return _arrow("news.list", {"ticker": ticker, "limit": limit})


# --- auth / trades / user data ------------------------------------------------
def auth_login(login: str, password: str) -> dict:
    return _json("auth.login", {"login": login, "password": password})


def auth_logout(token: str) -> dict:
    return _json("auth.logout", {"token": token})


def trades_list(token: str) -> pd.DataFrame:
    return _arrow("trades.list", {"token": token})


def trades_book(
    token: str,
    portfolio: str,
    position: str,
    buysell: str,
    legsym: str,
    px: float,
    qty: float,
    upx: float | None = None,
    uvol: float | None = None,
    tradedt: Any = None,
) -> dict:
    return _json(
        "trades.book",
        {
            "token": token,
            "portfolio": portfolio,
            "position": position,
            "buysell": buysell,
            "legsym": legsym,
            "px": float(px),
            "qty": float(qty),
            "upx": None if upx is None else float(upx),
            "uvol": None if uvol is None else float(uvol),
            "tradedt": iso_date(tradedt),
        },
    )


def config_queries() -> pd.DataFrame:
    return _arrow("config.queries")


def config_reports() -> pd.DataFrame:
    return _arrow("config.reports")


def predictions_list() -> pd.DataFrame:
    return _arrow("predictions.list")


def predictions_add(ticker: str, tgtpx: float, mktpx: float) -> dict:
    return _json("predictions.add", {"ticker": ticker, "tgtpx": float(tgtpx), "mktpx": float(mktpx)})


# --- backtest -----------------------------------------------------------------
def backtest_shadow(ticker: str, initdt: Any, optvol: float | None = None, riskvol: float | None = None) -> pd.DataFrame:
    return _arrow(
        "backtest.shadow",
        {
            "ticker": ticker,
            "initdt": iso_date(initdt),
            "optvol": None if optvol is None else float(optvol),
            "riskvol": None if riskvol is None else float(riskvol),
        },
    )
