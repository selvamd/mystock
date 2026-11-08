"""Cached market data service: the legacy ``globalcontext`` + ``stock_screener`` behind a TTL.

Legacy Streamlit cached ``globalcontext()`` forever with ``st.cache_data``. Here the context
is rebuilt after ``MYSTOCK_CONTEXT_TTL`` seconds (default 600) or as soon as the backend's
``ping`` reports a new ``db_date`` (checked at most every ``MYSTOCK_CONTEXT_PING`` seconds).
Each gunicorn worker holds its own copy. If a rebuild fails while an older context exists,
the old one keeps being served (and the rebuild is retried on the next ping interval).
"""
from __future__ import annotations

import logging
import threading
import time

import pandas as pd

from . import api, config
from .legacy import svcreader
from .legacy.myutils import stock_screener
from .legacy.option import mleg
from .legacy.positions import myposition

log = logging.getLogger(__name__)

_lock = threading.RLock()
_state: dict = {"ss": None, "built": 0.0, "db_date": None, "pinged": 0.0}
_static: dict = {}


def _build():
    ctx = svcreader.globalcontext()
    ss = stock_screener(ctx, False)
    mleg.screener = ss  # legacy: sapp.py sets mleg.screener = ss at import time
    return ss


def get_screener() -> stock_screener:
    """Return the cached ``stock_screener`` (``ss``); ``ss.ctx`` is the ``globalcontext``."""
    now = time.monotonic()
    with _lock:
        ss = _state["ss"]
        stale = ss is None or now - _state["built"] > config.CONTEXT_TTL_SECONDS
        if not stale and now - _state["pinged"] > config.CONTEXT_PING_SECONDS:
            _state["pinged"] = now
            try:
                db_date = api.ping().get("db_date")
                if db_date != _state["db_date"]:
                    log.info("db_date changed %s -> %s; rebuilding market context", _state["db_date"], db_date)
                    stale = True
            except Exception:  # backend blip: keep serving the cached context
                log.warning("ping failed; serving cached market context", exc_info=True)
        if not stale:
            mleg.screener = ss
            return ss
        try:
            db_date = api.ping().get("db_date")
            svcreader.clear_cache()
            ss = _build()
        except Exception:
            if _state["ss"] is not None:
                log.warning("market context rebuild failed; serving previous one", exc_info=True)
                _state["built"] = now - config.CONTEXT_TTL_SECONDS + config.CONTEXT_PING_SECONDS
                mleg.screener = _state["ss"]
                return _state["ss"]
            raise
        _state.update(ss=ss, built=now, db_date=db_date, pinged=now)
        _static.clear()
        return ss


def get_context():
    return get_screener().ctx


def invalidate() -> None:
    with _lock:
        _state.update(ss=None, built=0.0, db_date=None, pinged=0.0)
        _static.clear()
    svcreader.clear_cache()


def _static_csv(name: str, cnt: int) -> pd.DataFrame:
    with _lock:
        if name not in _static:
            _static[name] = svcreader.request_csv(name, cnt)
        return _static[name].copy()


def get_queries() -> pd.DataFrame:
    """Legacy ``get_queries``: ``request_csv("queries", 100)`` (-> ``config.queries``)."""
    return _static_csv("queries", 100)


def get_dynamic_reports_data() -> pd.DataFrame:
    """Legacy ``get_dynamic_reports_data`` (-> ``config.reports``) with ``showtext``."""
    df = _static_csv("dynamic_reports", 1000)
    df["showtext"] = df.function + ":" + df.report
    return df


def get_portfolio(login: str, token: str) -> myposition:
    """Legacy ``getPortfolioCache(login, session)``.

    Built fresh per request (one ``trades.list`` call): ``myposition`` objects are mutated by
    ``spot()``/``set()``, so sharing one between concurrent Dash callbacks would race.
    """
    get_screener()  # positions price off mleg.screener
    return myposition(login, "p0", str(token))
