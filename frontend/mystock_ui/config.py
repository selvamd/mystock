"""Environment-driven configuration (names as in the repo-level ``.env.example``)."""
from __future__ import annotations

import os


def _int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


def _float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


API_HOST = os.environ.get("MYSTOCK_API_HOST", "localhost")
API_PORT = _int("MYSTOCK_API_PORT", 9090)
API_KEY = os.environ.get("MYSTOCK_API_KEY", "")
API_CONNECT_TIMEOUT = _float("MYSTOCK_API_CONNECT_TIMEOUT", 5.0)
# options.chain / fundamentals may hit Alpha Vantage server-side, so reads get a generous timeout.
API_READ_TIMEOUT = _float("MYSTOCK_API_READ_TIMEOUT", 120.0)

UI_PORT = _int("MYSTOCK_UI_PORT", 8443)
UI_SECRET = os.environ.get("MYSTOCK_UI_SECRET", "")
TLS_CERT = os.environ.get("MYSTOCK_TLS_CERT", "")
TLS_KEY = os.environ.get("MYSTOCK_TLS_KEY", "")

# Market-context cache (port of legacy globalcontext): rebuilt after this many seconds,
# or earlier when ping's db_date changes (checked at most every CONTEXT_PING_SECONDS).
CONTEXT_TTL_SECONDS = _int("MYSTOCK_CONTEXT_TTL", 600)
CONTEXT_PING_SECONDS = _int("MYSTOCK_CONTEXT_PING", 60)
# Short TTL cache for time-series reads (metrics/prices) used by charts and pricers.
SERIES_TTL_SECONDS = _int("MYSTOCK_SERIES_TTL", 300)
