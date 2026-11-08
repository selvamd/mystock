"""Socket client for the mystock backend (protocol v1, see docs/PROTOCOL.md).

Framing: ``uint32 big-endian length || payload``.

* Request: one JSON frame ``{"v":1,"id","key","op","args"}``.
* Response: one JSON header frame, then depending on ``format``:
  ``arrow`` -> a raw (not length-prefixed) Arrow IPC stream, ``json`` -> one JSON frame,
  ``none`` -> nothing (always the case for ``status=error``).

Connections are persistent and thread-local (one request at a time per connection).
A connection that turns out to be stale (broken pipe / EOF before any byte of the
response header) is re-opened once and the request is re-sent.
"""
from __future__ import annotations

import json
import logging
import os
import socket
import struct
import threading
import uuid
from dataclasses import dataclass
from typing import Any

import pyarrow as pa
import pyarrow.ipc as ipc

from . import config

log = logging.getLogger(__name__)

PROTOCOL_VERSION = 1
MAX_FRAME = 256 * 1024 * 1024  # sanity guard against garbage length prefixes

# Client-side error codes (not sent by the server; the server codes are BAD_REQUEST,
# UNAUTHORIZED, FORBIDDEN, NOT_FOUND, UPSTREAM, INTERNAL).
UNAVAILABLE = "UNAVAILABLE"  # cannot connect / connection dropped
TIMEOUT = "TIMEOUT"  # read or connect timed out
BAD_RESPONSE = "BAD_RESPONSE"  # malformed frame / unexpected format


class ApiError(Exception):
    """Error returned by the backend (``status=error``) or raised by the transport."""

    def __init__(self, code: str, message: str = ""):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class _StaleConnection(Exception):
    """EOF/reset before any response byte: the persistent connection was dead."""


@dataclass
class Response:
    header: dict
    format: str
    body: Any  # pa.Table for arrow, decoded JSON for json, None for none


def _recv_exact(rfile, n: int, *, first: bool = False) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = rfile.read(n - len(buf))
        if not chunk:
            if first and not buf:
                raise _StaleConnection()
            raise ApiError(UNAVAILABLE, "connection closed by backend mid-response")
        buf += chunk
    return bytes(buf)


def write_frame(wfile, payload: bytes) -> None:
    wfile.sendall(struct.pack(">I", len(payload)) + payload)


def read_frame(rfile, *, first: bool = False) -> bytes:
    (n,) = struct.unpack(">I", _recv_exact(rfile, 4, first=first))
    if n > MAX_FRAME:
        raise ApiError(BAD_RESPONSE, f"frame too large ({n} bytes)")
    return _recv_exact(rfile, n) if n else b""


class _Conn:
    def __init__(self, sock: socket.socket):
        self.sock = sock
        # One buffered reader for every read on this connection (headers, JSON bodies
        # and Arrow streams) so no bytes are ever split between two buffers.
        self.rfile = sock.makefile("rb")
        self.pid = os.getpid()

    def close(self) -> None:
        for c in (self.rfile, self.sock):
            try:
                c.close()
            except OSError:
                pass


class Client:
    def __init__(
        self,
        host: str | None = None,
        port: int | None = None,
        key: str | None = None,
        connect_timeout: float | None = None,
        read_timeout: float | None = None,
    ):
        self.host = host if host is not None else config.API_HOST
        self.port = int(port if port is not None else config.API_PORT)
        self.key = key if key is not None else config.API_KEY
        self.connect_timeout = connect_timeout if connect_timeout is not None else config.API_CONNECT_TIMEOUT
        self.read_timeout = read_timeout if read_timeout is not None else config.API_READ_TIMEOUT
        self._local = threading.local()
        self.connects = 0  # number of sockets opened (used by tests to prove reuse)

    # -- connection management -------------------------------------------------
    def _get_conn(self) -> _Conn:
        conn: _Conn | None = getattr(self._local, "conn", None)
        if conn is not None and conn.pid != os.getpid():  # inherited across fork
            conn = None
        if conn is None:
            try:
                sock = socket.create_connection((self.host, self.port), timeout=self.connect_timeout)
            except socket.timeout as e:
                raise ApiError(TIMEOUT, f"connect to backend {self.host}:{self.port} timed out") from e
            except OSError as e:
                raise ApiError(UNAVAILABLE, f"backend {self.host}:{self.port} unreachable ({e})") from e
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(self.read_timeout)
            conn = _Conn(sock)
            self._local.conn = conn
            self.connects += 1
        return conn

    def _drop(self) -> None:
        conn = getattr(self._local, "conn", None)
        if conn is not None:
            conn.close()
        self._local.conn = None

    def close(self) -> None:
        self._drop()

    # -- request ---------------------------------------------------------------
    def call(self, op: str, args: dict | None = None) -> Response:
        req_id = uuid.uuid4().hex[:8]
        payload = json.dumps(
            {"v": PROTOCOL_VERSION, "id": req_id, "key": self.key, "op": op, "args": args or {}},
            default=str,
        ).encode("utf-8")
        for attempt in (0, 1):
            conn = self._get_conn()
            try:
                write_frame(conn.sock, payload)
                header_raw = read_frame(conn.rfile, first=True)
            except (_StaleConnection, BrokenPipeError, ConnectionResetError, ConnectionAbortedError) as e:
                self._drop()
                if attempt == 0:
                    log.info("stale backend connection (%s); reconnecting once", type(e).__name__)
                    continue
                raise ApiError(UNAVAILABLE, "backend closed the connection") from e
            except socket.timeout as e:
                self._drop()
                raise ApiError(TIMEOUT, f"backend did not answer {op} in time") from e
            except ApiError:
                self._drop()
                raise
            except OSError as e:
                self._drop()
                raise ApiError(UNAVAILABLE, f"backend I/O error ({e})") from e
            return self._read_response(conn, req_id, op, header_raw)
        raise ApiError(UNAVAILABLE, "unreachable")  # pragma: no cover

    def _read_response(self, conn: _Conn, req_id: str, op: str, header_raw: bytes) -> Response:
        try:
            header = json.loads(header_raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as e:
            self._drop()
            raise ApiError(BAD_RESPONSE, "malformed response header") from e
        if header.get("id") not in (None, req_id):
            self._drop()
            raise ApiError(BAD_RESPONSE, f"response id {header.get('id')} does not match request {req_id}")
        if header.get("status") != "ok":
            code = header.get("code") or "INTERNAL"
            if code == "UNAUTHORIZED" and op != "auth.login":
                self._drop()  # server closes the connection after a bad API key
            raise ApiError(code, header.get("error") or "")
        fmt = header.get("format", "none")
        try:
            if fmt == "arrow":
                reader = ipc.open_stream(conn.rfile)
                body = reader.read_all()
            elif fmt == "json":
                body = json.loads(read_frame(conn.rfile).decode("utf-8"))
            elif fmt == "none":
                body = None
            else:
                raise ApiError(BAD_RESPONSE, f"unknown body format {fmt!r}")
        except ApiError:
            self._drop()
            raise
        except socket.timeout as e:
            self._drop()
            raise ApiError(TIMEOUT, f"backend timed out while streaming {op}") from e
        except (OSError, pa.ArrowException, ValueError) as e:
            self._drop()
            raise ApiError(BAD_RESPONSE, f"could not read {fmt} body of {op} ({e})") from e
        return Response(header=header, format=fmt, body=body)


_default: Client | None = None
_default_lock = threading.Lock()


def get_client() -> Client:
    global _default
    if _default is None:
        with _default_lock:
            if _default is None:
                _default = Client()
    return _default


def set_client(client: Client | None) -> None:
    """Replace the process-wide client (tests, scripts)."""
    global _default
    with _default_lock:
        _default = client
