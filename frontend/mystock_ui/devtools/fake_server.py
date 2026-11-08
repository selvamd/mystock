"""In-process fake backend speaking protocol v1 (docs/PROTOCOL.md) over the fixture data.

For tests and local UI development only. Behaviour knobs:
``fail_ops`` maps an op name to an error code to return (e.g. ``{"news.list": "UPSTREAM"}``).
"""
from __future__ import annotations

import json
import socketserver
import struct
import threading

import pandas as pd
import pyarrow as pa
import pyarrow.ipc as ipc

from .fixtures import Fixtures

DATE_COLS = {"asof", "date", "ymd", "tradedt", "expiration", "fiscal_date"}


def to_arrow(df: pd.DataFrame) -> pa.Table:
    fields, arrays = [], []
    for c in df.columns:
        s = df[c]
        if c in DATE_COLS and pd.api.types.is_datetime64_any_dtype(s):
            arr = pa.array(pd.to_datetime(s).dt.date, type=pa.date32())
        elif pd.api.types.is_datetime64_any_dtype(s):
            arr = pa.array(pd.to_datetime(s).astype("datetime64[us]"), type=pa.timestamp("us"))
        elif pd.api.types.is_integer_dtype(s):
            arr = pa.array(s.astype("int64"), type=pa.int64())
        elif pd.api.types.is_float_dtype(s):
            arr = pa.array(s.astype(float), type=pa.float64())
        else:
            arr = pa.array(s.astype(str), type=pa.utf8())
        fields.append(pa.field(c, arr.type))
        arrays.append(arr)
    return pa.Table.from_arrays(arrays, schema=pa.schema(fields))


class _Err(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code, self.msg = code, msg


class FakeBackend:
    def __init__(self, key: str = "test-key", host: str = "127.0.0.1", port: int = 0, fixtures: Fixtures | None = None):
        self.key = key
        self.fx = fixtures or Fixtures()
        self.fail_ops: dict[str, str] = {}
        self.connections = 0
        self.requests: list[dict] = []
        self.lock = threading.Lock()
        backend = self

        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                with backend.lock:
                    backend.connections += 1
                while True:
                    hdr = self.rfile.read(4)
                    if len(hdr) < 4:
                        return
                    (n,) = struct.unpack(">I", hdr)
                    req = json.loads(self.rfile.read(n))
                    with backend.lock:
                        backend.requests.append(req)
                    if not backend.respond(self.wfile, req):
                        return

        class Server(socketserver.ThreadingTCPServer):
            allow_reuse_address = True
            daemon_threads = True

        self.server = Server((host, port), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def start(self):
        self.thread.start()
        return self

    def stop(self):
        self.server.shutdown()
        self.server.server_close()

    # ----------------------------------------------------------------- framing
    @staticmethod
    def _frame(w, obj):
        b = json.dumps(obj, default=str).encode()
        w.write(struct.pack(">I", len(b)) + b)

    def respond(self, w, req) -> bool:
        rid = req.get("id")
        if req.get("key") != self.key:
            self._frame(w, {"v": 1, "id": rid, "status": "error", "code": "UNAUTHORIZED", "error": "bad api key"})
            w.flush()
            return False  # close connection, as the spec says
        if req.get("v") != 1:
            self._frame(w, {"v": 1, "id": rid, "status": "error", "code": "BAD_REQUEST", "error": "bad version"})
            return True
        try:
            if req["op"] in self.fail_ops:
                raise _Err(self.fail_ops[req["op"]], f"simulated failure of {req['op']}")
            kind, body = self.dispatch(req["op"], req.get("args") or {})
        except _Err as e:
            self._frame(w, {"v": 1, "id": rid, "status": "error", "code": e.code, "error": e.msg})
            return True
        except Exception as e:  # noqa: BLE001
            self._frame(w, {"v": 1, "id": rid, "status": "error", "code": "INTERNAL", "error": str(e)})
            return True
        if kind == "arrow":
            table = to_arrow(body)
            self._frame(w, {"v": 1, "id": rid, "status": "ok", "format": "arrow", "rows": table.num_rows})
            with ipc.new_stream(w, table.schema) as writer:
                writer.write_table(table)
        elif kind == "json":
            self._frame(w, {"v": 1, "id": rid, "status": "ok", "format": "json"})
            self._frame(w, body)
        else:
            self._frame(w, {"v": 1, "id": rid, "status": "ok", "format": "none"})
        w.flush()
        return True

    # ---------------------------------------------------------------- dispatch
    def _ticker(self, a, name="ticker"):
        t = str(a.get(name, "")).upper()
        if not self.fx.known(t):
            raise _Err("NOT_FOUND", f"unknown ticker {t}")
        return t

    def _user(self, a):
        user = self.fx.sessions.get(a.get("token", ""))
        if not user:
            raise _Err("FORBIDDEN", "bad or expired session token")
        return user

    def dispatch(self, op, a):
        fx = self.fx
        if op == "ping":
            return "json", {"pong": True, "db_date": fx.db_date.isoformat(), "version": "fake"}
        if op == "admin.status":
            return "json", {"db_date": fx.db_date.isoformat(), "symbols": len(fx.screener_stocks()), "jobs": []}
        if op == "admin.reload":
            return "json", {"reloaded": True}
        if op == "admin.run_job":
            return "json", {"accepted": True}
        if op == "screener.stocks":
            df = fx.screener_stocks()
            return "arrow", df.head(a["limit"]) if a.get("limit") else df
        if op == "screener.portfolios":
            return "arrow", fx.screener_portfolios(a.get("wgtype"))
        if op == "prices.history":
            return "arrow", fx.history(self._ticker(a))
        if op == "prices.ohlc":
            return "arrow", fx.prices_ohlc(self._ticker(a), a.get("start"), a.get("end"))
        if op == "prices.asof":
            return "arrow", fx.prices_asof(a["date"], a["field"], a["tickers"])
        if op == "prices.series":
            return "arrow", fx.prices_series(str(a["ticker"]), a["kind"])
        if op == "metrics.series":
            return "arrow", fx.metric(self._ticker(a), a["metric"])
        if op == "metrics.batch":
            for t in a["tickers"]:
                self._ticker({"ticker": t})
            return "arrow", fx.metrics_batch(a["tickers"], a["metrics"], a.get("since"))
        if op == "quote.live":
            return "json", fx.quote_live(self._ticker(a))
        if op == "market.vix":
            return "arrow", fx.market_vix(a.get("days"))
        if op == "options.chain":
            return "arrow", fx.options_chain(self._ticker(a))
        if op == "fundamentals.statements":
            return "arrow", fx.fundamentals(self._ticker(a))
        if op == "news.list":
            return "arrow", fx.news(self._ticker(a), a.get("limit", 10))
        if op == "auth.login":
            tok = fx.login(a.get("login"), a.get("password"))
            if not tok:
                raise _Err("UNAUTHORIZED", "invalid login")
            return "json", {"token": tok, "login": a["login"], "expires_at": "2099-01-01T00:00:00"}
        if op == "auth.logout":
            fx.sessions.pop(a.get("token", ""), None)
            return "json", {"ok": True}
        if op == "trades.list":
            return "arrow", fx.trades_list(self._user(a))
        if op == "trades.book":
            return "json", {"trade_id": fx.trades_book(self._user(a), a)}
        if op == "config.queries":
            return "arrow", fx.queries()
        if op == "config.reports":
            return "arrow", fx.reports()
        if op == "predictions.list":
            return "arrow", fx.predictions
        if op == "predictions.add":
            return "json", {"ok": True}
        if op == "backtest.shadow":
            return "arrow", fx.shadow(self._ticker(a), a["initdt"])
        raise _Err("BAD_REQUEST", f"unknown op {op}")
