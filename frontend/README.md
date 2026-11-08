# mystock frontend (Plotly Dash)

A Dash port of the legacy Streamlit app `mystk/python/sapp.py`. It has the same 21 pages in
the same order, and all pages except Login stay hidden until you log in.

**Rule:** the frontend never opens a database and never calls a third-party data API.
All data comes from the Java backend over the socket protocol in
[`docs/PROTOCOL.md`](../docs/PROTOCOL.md). The external sites in the "urls" header (Yahoo,
Finviz and so on) are plain hyperlinks.

## Layout

```
mystock_ui/
  client.py        protocol client: thread-local persistent socket, timeouts,
                   reconnect-once on a stale connection, Arrow IPC bodies, ApiError(code, message)
  api.py           one typed function per op -> pandas DataFrame / dict
  data.py          cached market context (legacy globalcontext + stock_screener), TTL 10 min,
                   rebuilt when ping's db_date changes; config.queries / config.reports cache
  legacy/          legacy modules copied as-is from mystk/python (every changed line is marked # CHANGED:)
    optionprice.py option.py positions.py plots.py myutils.py utils.py   (math kept as-is)
    svcreader.py   drop-in replacement for the old socket client (same function names and frame
                   shapes, now built on api.py) + the original globalcontext/qdropratio/dpnl/cross/tier
    myglobal.py    legacy IPython startup script
    shell.py       `from mystock_ui.legacy.shell import *` (legacy setup.py equivalent)
  pages/           one module per page (Dash layout + callbacks); common.py = widgets, AG Grid
                   tables, friendly errors, explicit function registry (replaces eval dispatch)
  devtools/        fixture data + in-process fake backend (tests / local development only)
  app.py           Dash app; `server` is the WSGI app for gunicorn
scripts/fake_backend.py   run the fake backend: `python scripts/fake_backend.py <port> <key>`
tests/                    client, legacy-parity and page smoke tests
```

## Configuration (env, see `../.env.example`)

| var | default | |
|---|---|---|
| `MYSTOCK_API_HOST` / `MYSTOCK_API_PORT` | `localhost` / `9090` | backend socket |
| `MYSTOCK_API_KEY` | – | shared secret, must match the backend |
| `MYSTOCK_UI_PORT` | `8443` | HTTP(S) port |
| `MYSTOCK_UI_SECRET` | random | Flask secret key |
| `MYSTOCK_TLS_CERT` / `MYSTOCK_TLS_KEY` | – | when both are set, gunicorn serves HTTPS |
| `MYSTOCK_CONTEXT_TTL` / `MYSTOCK_CONTEXT_PING` | `600` / `60` | market-context cache TTL / db_date check interval (s) |
| `MYSTOCK_SERIES_TTL` | `300` | cache for metrics/price series reads (s) |
| `MYSTOCK_API_CONNECT_TIMEOUT` / `MYSTOCK_API_READ_TIMEOUT` | `5` / `120` | socket timeouts (s) |

## Run

```bash
cd frontend
python3.12 -m venv .venv && .venv/bin/pip install -r requirements.txt

# against the fake backend (login demo/demo)
.venv/bin/python scripts/fake_backend.py 9090 devkey &
MYSTOCK_API_HOST=127.0.0.1 MYSTOCK_API_PORT=9090 MYSTOCK_API_KEY=devkey \
MYSTOCK_UI_PORT=8050 .venv/bin/python -m mystock_ui          # dev server on 127.0.0.1:8050

# production-style
MYSTOCK_API_HOST=... MYSTOCK_API_KEY=... \
.venv/bin/gunicorn -w 2 --threads 4 --timeout 180 -b 0.0.0.0:8443 mystock_ui.app:server

# docker (from the repo root; compose also wires the backend)
docker build -t mystock-frontend frontend
docker compose up frontend
```

Each gunicorn worker keeps its own market-context cache and its own backend connections
(one per thread).

## IPython (legacy interactive workflow)

```python
# MYSTOCK_API_HOST / MYSTOCK_API_KEY set; optional MYSTOCK_SHELL_LOGIN / MYSTOCK_SHELL_PASSWORD
from mystock_ui.legacy.shell import *
ss('movers', -5.0, 'weekpnl', 20.0, None)
ss.zviz.tsstock('AAPL', 365).show()
o = ss('opricer', 'AAPL', Stgy.peg_condor, '', ovar(0, 0, 45)); print(o)
sel.pnl()          # only when MYSTOCK_SHELL_LOGIN is set (legacy auto-logged in as 'Sel')
ctx.predict('AAPL', mktpx=180, tgtpx=200)   # now persisted via predictions.add
```

This gives the same names as the legacy `myglobal.py` startup: `ctx`, `ss`, `q`, `feat`, `ssf`,
`ssq`, `portfolio(name, sess)`, `session`, `sel`, and the module-level helpers.

## Tests

```bash
.venv/bin/python -m pytest
```

* `tests/test_client.py` runs framing, Arrow/JSON/error bodies, API-key rejection, persistent
  connection reuse, reconnect-once, backend-down and UPSTREAM checks against in-process fake servers.
* `tests/test_legacy_parity.py` checks that every top-level name of each legacy file still
  exists. It also imports the originals from `../mystk/python` and asserts identical results
  on the same inputs: option prices/greeks, strike solvers, Stgy templates, dpnl/cross/tier/qdropratio,
  screener functions, option surfaces, finstmt, tposition PnL/greeks/plots, and mleg pricer
  tables. A final test covers the IPython shell startup.
* `tests/test_pages.py` renders every page and callback against the fake backend: the
  logged-out state, login/logout, booking, backend down, UPSTREAM (news/options/fundamentals/vix),
  expired session, and unknown report function.
