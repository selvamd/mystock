"""Protocol client tests against in-process fake servers."""
import json
import socket
import struct
import threading

import pandas as pd
import pyarrow as pa
import pyarrow.ipc as ipc
import pytest

from mystock_ui import api, client
from mystock_ui.client import ApiError, Client

from .conftest import KEY


def test_arrow_json_and_reuse(live):
    c = client.get_client()
    before = live.connections
    df = api.screener_stocks()
    assert list(df.columns)[:3] == ["asof", "ticker", "name"]
    assert pd.api.types.is_datetime64_any_dtype(df["asof"])
    assert api.ping()["pong"] is True
    h = api.prices_history("aapl")
    assert list(h.columns) == ["asof", "open", "high", "low", "close", "adjclose", "volume"]
    for _ in range(5):
        api.quote_live("AAPL")
    assert c.connects == 1 and live.connections == before + 1  # one persistent connection
    req = live.requests[-1]
    assert req["v"] == 1 and req["key"] == KEY and req["op"] == "quote.live" and len(req["id"]) == 8


def test_error_frame(live):
    with pytest.raises(ApiError) as e:
        api.prices_history("ZZZZ")
    assert e.value.code == "NOT_FOUND"
    # connection still usable after an error response
    assert api.ping()["pong"]
    assert client.get_client().connects == 1


def test_bad_api_key_closes(live):
    c = Client("127.0.0.1", live.port, "wrong", connect_timeout=2, read_timeout=5)
    with pytest.raises(ApiError) as e:
        c.call("ping")
    assert e.value.code == "UNAUTHORIZED"


def test_backend_down():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    c = Client("127.0.0.1", port, KEY, connect_timeout=1, read_timeout=1)
    with pytest.raises(ApiError) as e:
        c.call("ping")
    assert e.value.code == "UNAVAILABLE"


def test_upstream_error(live):
    live.fail_ops["news.list"] = "UPSTREAM"
    with pytest.raises(ApiError) as e:
        api.news_list("AAPL")
    assert e.value.code == "UPSTREAM"


class OneShotServer:
    """Answers one request per connection with a hand-built frame, then closes it."""

    def __init__(self, table=None, body=None):
        self.table, self.body = table, body
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", 0))
        self.sock.listen(5)
        self.port = self.sock.getsockname()[1]
        self.accepted = 0
        threading.Thread(target=self.loop, daemon=True).start()

    def loop(self):
        while True:
            try:
                conn, _ = self.sock.accept()
            except OSError:
                return
            self.accepted += 1
            f = conn.makefile("rwb")
            n = struct.unpack(">I", f.read(4))[0]
            req = json.loads(f.read(n))
            if self.table is not None:
                hdr = json.dumps({"v": 1, "id": req["id"], "status": "ok", "format": "arrow"}).encode()
                f.write(struct.pack(">I", len(hdr)) + hdr)
                with ipc.new_stream(f, self.table.schema) as w:
                    if self.table.num_rows:
                        w.write_table(self.table)
            else:
                hdr = json.dumps({"v": 1, "id": req["id"], "status": "ok", "format": "json"}).encode()
                b = json.dumps(self.body).encode()
                f.write(struct.pack(">I", len(hdr)) + hdr + struct.pack(">I", len(b)) + b)
            f.flush()
            f.close()
            conn.close()  # server drops the persistent connection


def test_empty_arrow_stream_and_reconnect_once():
    schema = pa.schema([("asof", pa.date32()), ("value", pa.float64())])
    srv = OneShotServer(table=schema.empty_table())
    c = Client("127.0.0.1", srv.port, KEY, connect_timeout=2, read_timeout=5)
    r1 = c.call("prices.series", {"ticker": "X", "kind": "STK"})
    assert r1.format == "arrow" and r1.body.num_rows == 0 and r1.body.schema.names == ["asof", "value"]
    # the server closed the connection: the next call must transparently reconnect once
    r2 = c.call("prices.series", {"ticker": "X", "kind": "STK"})
    assert r2.body.num_rows == 0
    assert c.connects == 2 and srv.accepted == 2


def test_date32_to_datetime():
    t = pa.table({"asof": pa.array([pd.Timestamp("2024-01-02").date()], pa.date32()), "v": [1.0]})
    df = api.table_to_df(t)
    assert str(df["asof"].dtype) == "datetime64[ns]"


def test_iso_date():
    assert api.iso_date(20240105) == "2024-01-05"
    assert api.iso_date("2024-01-05") == "2024-01-05"
    assert api.iso_date(pd.Timestamp("2024-01-05")) == "2024-01-05"
    assert api.iso_date(None) is None
