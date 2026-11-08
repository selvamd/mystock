# mystock socket protocol (v1)

Transport: TCP, default port `9090` (`MYSTOCK_API_PORT`). Connections are **persistent**: a client may send many requests on one connection, strictly one at a time (send a request, read its full response, send the next). The server handles each connection on its own (virtual) thread.

## Framing

```
frame := uint32 big-endian length N  ||  N bytes payload
```

### Request
One frame whose payload is UTF-8 JSON:

```json
{"v": 1, "id": "c0ffee01", "key": "<MYSTOCK_API_KEY>", "op": "prices.ohlc", "args": {"ticker": "AAPL"}}
```

* `key` must equal the server's `MYSTOCK_API_KEY` (shared secret between containers). If it does not match, the server returns `status=error, code=UNAUTHORIZED` and closes the connection.
* `id` is echoed back in the response header.
* Ticker arguments are case-insensitive. The server upper-cases them.
* Dates in `args` are ISO strings `"YYYY-MM-DD"`. Legacy `yyyymmdd` integers are also accepted.

### Response
1. A **header frame** (UTF-8 JSON):
   ```json
   {"v": 1, "id": "c0ffee01", "status": "ok", "format": "arrow", "rows": 1234}
   {"v": 1, "id": "c0ffee01", "status": "error", "code": "NOT_FOUND", "error": "unknown ticker ZZZZ"}
   ```
   `format` is one of `arrow`, `json`, or `none`. `rows` is optional, and present when known. `code` is one of `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN` (bad/expired session token), `NOT_FOUND`, `UPSTREAM` (third-party API failed), `INTERNAL`.
2. A body, depending on `format`:
   * `arrow`: a raw **Arrow IPC stream** (schema message, record batches, end-of-stream marker) written directly after the header. It is self-delimiting, so read it with `pyarrow.ipc.open_stream(sock_file)`. It is never length-prefixed. An empty result is a valid stream with a schema and zero batches.
   * `json`: one frame holding a UTF-8 JSON value.
   * `none`: no body (always the case when `status=error`).

### Column type rules (Arrow)
* Dates → `date32` (pandas: `datetime64`). Timestamps → `timestamp[us]`.
* Numbers → `float64` or `int32`/`int64`. **No DECIMAL**: the server casts DuckDB DECIMALs to DOUBLE.
* Text → `utf8`. Column names are `snake_case` and exactly as listed below.

## Operations

Unless stated otherwise, `ticker` arguments must exist in `symbols`, or the response is `NOT_FOUND`.

### Health / admin
| op | args | response |
|---|---|---|
| `ping` | – | json `{"pong": true, "db_date": "YYYY-MM-DD", "version": "…"}` |
| `admin.status` | – | json `{"db_date", "symbols", "jobs": [{"job","status","started_at","finished_at","message"}]}` |
| `admin.run_job` | `job` (one of `SYMBOLS, SYMBOLS_FULL, PRICES, STOCKINFO, STOCKFEAT, MODEL, VOL_ONCE, OPTIONS, OPTFEAT, FINANCIALS, EOD`) | json `{"accepted": true}`. The job runs asynchronously, one at a time. |
| `admin.reload` | – | json `{"reloaded": true}`. Rebuilds the in-memory market context (legacy `INIT`). |

### Screener snapshots (legacy `CSV,out` / `CSV,port`)
| op | args | response |
|---|---|---|
| `screener.stocks` | `limit?` (default all) | arrow, latest `asof` of `stock_metrics`, one row per ticker. Columns, in this order: `asof, ticker, name, sect, ind, mktsize, symid, mktcap, pe, eps, dividend, shares, ttmearnings, ttmbookvalue, ttmrevenue, ttmgrossprofit, daypnl, weekpnl, mompnl, qoqpnl, yoypnl, pnldir, cagr, corr, upcorr, downcorr, coskew, range, rangescore, lastdevz, seasonality, seasonindex, xadv, avgdr, momtr, px2bbpct, volratio, signal, aroonup, aroondwn, hangtime, peak30, drop30, qpeak, prscore, qdrop, qrebound, precovpx, decovpx, kurt, trisktouch, triskbreach, triskvol, qdropavg, volratioyy, maxpctchg, sharpness, volscore, histvol, optvol, optivol, rangevol, rsi, weekdip, momdip, bumpiness, r2, maxdrs, mindrs, stlpm, dtpm, ema20, ema60, ema200, beta, alpha, lastpx, mktrscore, mktdip, mktsindex, summ, impvol, earnings, income, revenue`. `mktcap` is in raw dollars, as in legacy. `summ` keeps the legacy `u`/`d` arrow encoding. `earnings` is days to the next earnings date (365 if unknown). `income`/`revenue` are the legacy year-over-year 0/1 growth strings. |
| `screener.portfolios` | `wgtype?` (`EW`/`MW`) | arrow, latest `asof` of `portfolio_metrics`. Columns: `asof, name, daypnl, weekpnl, mompnl, qoqpnl, yoypnl, roi, corr, upcorr, downcorr, coskew, seasonality, seasonindex, aroonup, aroondwn, hangtime, peak30, drop30, px2bbpct, qpeak, momtr, rangescore, prscore, qdrop, qrebound, range, lastdevz, optvol, optivol, kurt, trisktouch, triskbreach, rsi, weekdip, momdip, bumpiness, r2, histvol, stlpm, dtpm, ema20, ema60, ema200, beta, alpha, mktrscore, mktdip, mktsindex, wgtype`. `name` is e.g. `TECH/MW`. |

### Prices
| op | args | response |
|---|---|---|
| `prices.history` | `ticker` | arrow `asof, open, high, low, close, adjclose, volume`. Raw unadjusted OHLC in dollars, plus `adjclose`. Replaces legacy `QRY,PRICE` (the frontend no longer divides by 100). |
| `prices.ohlc` | `ticker`, `start?`, `end?` | arrow `asof, open, high, low, close, prev, volume`. All prices **split/dividend-adjusted** (each field × adjclose/close, as legacy `PriceDB.loadField`). `prev` is the previous trading day's adjusted close. Replaces the five legacy `OHLC,<fld>,…` calls with one. |
| `prices.asof` | `date`, `field` (`open/high/low/close/prev/volume`), `tickers` (list) | arrow `ticker, value`, in request order. A missing value is 0.0. If `date` has no data, the next trading day is used (legacy behaviour). |
| `prices.series` | `ticker`, `kind` (`STK`/`EWT`/`MWT`/`AWT`) | arrow `asof, value`. `STK`: standardized adjusted close (last value = 100) for a stock. `EWT`/`MWT`: equal/market-weighted series of a portfolio (`MKT`, `TECH`, `FANG`, …), re-standardized to last = 100. `AWT`: raw market-weighted aggregate value, in $B. Points are emitted exactly as legacy `TimeSeriesData.load` produced them: sparse change points on trading days, not a dense calendar series. |

### Time-series metrics (legacy `METRIC`)
| op | args | response |
|---|---|---|
| `metrics.series` | `ticker`, `metric` | arrow `asof, value`. `metric` ∈ `OPEN,HIGH,LOW,PREV,MKTPX,RSI,STDMKTPX,ROI,DAYPNL,WEEKPNL,MOMPNL,QOQPNL,YOYPNL,DTPM,STLPM,OPTVOL,OPTIVOL,EMA20,EMA60,EMA200,RANGESCORE,LASTDEVZ,CORR,BUMPINESS,PRSCORE,KURT,CCI,CCIQ,COSKEW,PCTCHG,ATR,ADV,TRMON,PNLDIR,MOMDIP`. Same semantics as `StockReportSvc.processMetrics`/`getDailyMatrics`. Metrics with no legacy implementation return an empty stream. |
| `metrics.batch` | `tickers` (list), `metrics` (list), `since?` (date) | arrow, long format `ticker, metric, asof, value`. Equivalent to calling `metrics.series` for every pair, filtered to `asof >= since`. Use it to avoid N round trips (legacy `tsmetricsdata`). |

### Live market data (all third-party calls happen server-side)
| op | args | response |
|---|---|---|
| `quote.live` | `ticker` | json `{"ticker", "spot_price", "spot_vol", "next_earnings"}` (`next_earnings` is ISO or null). Served from the server's live price/vol cache (legacy `PVCache`). The first call for a ticker registers it, and a background thread refreshes it every 2 minutes. `spot_vol` is IV in percent. Legacy `QRY,LIVE`. |
| `market.vix` | `days?` (default all) | arrow `date, vix, skew` (skew already ÷10, as legacy `cboevix`). Cached server-side for 1 hour. |
| `options.chain` | `ticker`, `refresh?` (bool) | arrow, the latest snapshot from `option_quotes` for the ticker: `contract_id, symbol, type, asof, expiration, strike, last, mark, bid, bid_size, ask, ask_size, volume, open_interest, implied_volatility, delta, gamma, theta, vega, rho, upx`. The server fetches live from Alpha Vantage `HISTORICAL_OPTIONS` (and stores it) if there is no snapshot from today or `refresh=true`. `type` is `C`/`P`. IV and greeks are plain decimals. Prices are in dollars. |
| `fundamentals.statements` | `ticker` | arrow, long format `statement, fiscal_date, period, field, value` (`statement` ∈ `income, balance`; `period` ∈ `annual, quarterly`). Fetched from Alpha Vantage and cached in `financials` for 7 days. |
| `news.list` | `ticker`, `limit?` (10) | arrow `published, author, url, title, sentiment, sentiment_reasoning`. Only items whose Polygon `insights` mention the ticker. Cached for 30 minutes. |

### Auth, trades, user data
A session `token` (opaque 32-hex string) is returned by `auth.login` and is required by the user-scoped ops. Sessions expire after 24h (legacy behaviour). A missing or expired token returns `FORBIDDEN`.

| op | args | response |
|---|---|---|
| `auth.login` | `login`, `password` | json `{"token", "login", "expires_at"}`, or `status=error, code=UNAUTHORIZED`. An existing valid session for the user is reused (legacy behaviour). |
| `auth.logout` | `token` | json `{"ok": true}` |
| `trades.list` | `token` | arrow `trade_id, login, portfolio, position, buysell, legsym, px, qty, tradedt, upx, uvol`. `tradedt` is a date. Legacy `QRY,TRADE`. |
| `trades.book` | `token, portfolio, position, buysell, legsym, px, qty`, optional `upx, uvol, tradedt` | json `{"trade_id"}`. If `upx`/`uvol` are omitted, the server fills them from `quote.live` of `legsym.split("@")[0]` and uses today as `tradedt`. **`qty == 0` deletes every trade of that (login, portfolio, position, legsym)**, as legacy `TRADE`. |
| `config.queries` | – | arrow `name, value` (legacy `queries.csv`) |
| `config.reports` | – | arrow `function, report, params` (legacy `dynamic_reports.csv`) |
| `predictions.list` | – | arrow `ticker, asof, tgtpx, open, high, low, close` |
| `predictions.add` | `ticker, tgtpx, mktpx` | json `{"ok": true}` (stored with today's date, open=high=low=close=mktpx) |

### Backtest
| op | args | response |
|---|---|---|
| `backtest.shadow` | `ticker`, `initdt`, `optvol?`, `riskvol?` | arrow. One row per simulated day of the `reversion` iron-condor strategy (legacy `SHADOW`). Columns: `ymd` (date of the simulated day, new) followed by legacy `ICBackTest.logheader()` + reversion extras: `upx, vol, risk, days, lput, sput, scall, lcall, netpx, netdelta, pnl, excesspnl, drift, weekpnl`. All columns except `ymd` are float64. `optvol`/`riskvol` are decimals (0.35); both omitted means the server estimates them, as legacy. |

## Versioning
`v` is the protocol version. The server rejects unknown major versions with `BAD_REQUEST`. New ops and new trailing columns are backward-compatible. Removing or renaming columns is not.
