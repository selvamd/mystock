# mystock — Architecture

This is a rewrite of the legacy `mystk/` application (kept untouched for reference).

## 1. Assessment of the legacy system

| Legacy component | What it does | Problems found |
|---|---|---|
| `stockdb` container → `TasksRunner` (Java) | With no args it loops forever scraping Yahoo for IV/earnings (`LoadVol`). With args (`EOD`, `FULL`, `PRICES`, …) it downloads the NASDAQ symbol list, Alpha Vantage overviews and daily prices, then computes KPI snapshots (`StockInfo` → `out.csv`, `StockFeat` → `port.csv`, `StockModel` → `train.csv`/`pred.csv`). | Eight SQLite files plus CSV files used as a data store. Prices stored as truncated integer cents. API keys hard-coded. Option expiry dates hard-coded to 2024. |
| `stockfeed` container → `StockReportSvc` (Java) | Socket server on port 8050 with a line protocol of `CMD,arg,…\n` → CSV lines → `EOM`. | Single thread polling sockets with a 1 ms timeout. No framing: a value containing a comma or newline breaks the stream. Errors are swallowed, so the client sees an empty result. `reload()` is a no-op. `FORECAST` is routed to `savetrade`. SQL is built by string concatenation (injectable). Passwords are stored in plain text. The session id is `currentTimeMillis()`. The port is published to the host. |
| `pyserver` container → Streamlit `sapp.py` | 21 pages: screener, charts, option pricer, trade booking, portfolio greeks, and more. | **Breaks the tier rule**: it calls Alpha Vantage, Polygon, Yahoo, Finnhub and CBOE directly, and `dbservice.py`/`findic.py` open SQLite files. Streamlit re-runs every page on every click, which is the reason for the `session()`/`textinput()` workarounds. Dispatch relies on `eval()`. |

### Does the proposed target make sense?

**Yes, with one structural change.**

* **DuckDB is a good fit.** The workload is analytical: around 12M price rows, window functions (`lag() over (partition by …)`), and full scans to build cross-sectional snapshots. The legacy code was already pushing SQLite into analytic SQL.
* **The socket API is a good fit, but the protocol needed replacing.** It now uses length-prefixed JSON requests with **Apache Arrow IPC** responses. DuckDB's JDBC driver exports query results directly as Arrow (`DuckDBResultSet.arrowExportStream`), and pyarrow turns them into pandas without copying. There is no CSV parsing, framing ambiguity or type guessing, and large results stream in batches. See [PROTOCOL.md](PROTOCOL.md).
* **Dash is a good fit.** Its callback model replaces Streamlit's re-run model and the session-state workarounds it required.
* **Structural change: the loader and the API service must run in one JVM.** DuckDB allows only **one process** to open a database file read-write, and while that process holds it, no other process can open it, not even read-only. The legacy design ran the loader and the service as separate containers sharing SQLite files. With DuckDB that would fail with lock errors whenever the loader runs, and the loader runs constantly (the `LoadVol` loop). The ETL jobs therefore run inside the backend on a scheduler, and can also be triggered on demand over the API (`admin.run_job`). The same jar has a CLI mode for one-off tasks such as migration and user setup, run while the server is stopped.

## 2. Target architecture

```
                 ┌──────────────────────────────────────────────────────┐
  Alpha Vantage  │ backend  (Java 21, single JVM, owns /data/*.duckdb)  │
  NASDAQ FTP ───▶│  ingest/   HTTP clients (AV, Yahoo, Finnhub, Polygon, │
  Yahoo, Polygon │            CBOE, NASDAQ)                              │
  CBOE, Finnhub  │  jobs/     scheduler + SYMBOLS, PRICES, STOCKINFO,    │
                 │            STOCKFEAT, MODEL, VOL, OPTIONS, OPTFEAT,   │
                 │            FINANCIALS, MIGRATE_SQLITE                 │
                 │  analytics/ TimeSeries, StockInfo, StockFeat, BS, …   │
                 │  backtest/  ICBackTest + strategies                   │
                 │  api/      socket server :9090 (Arrow IPC)            │
                 └───────────────▲──────────────────────────────────────┘
                                 │ length-prefixed JSON req / Arrow resp
                                 │ (docker network only, shared API key)
                 ┌───────────────┴──────────────────────────────────────┐
                 │ frontend (Python 3.12, Plotly Dash, gunicorn)        │
                 │  client/   socket client → pandas                    │
                 │  analytics/ screener, option pricing, positions      │
                 │  pages/    21 pages ported from sapp.py              │
                 └───────────────▲──────────────────────────────────────┘
                                 │ HTTPS :8443
                              browser
```

### Rules
1. The **frontend never opens a database and never calls a third-party data API.** Every byte comes through `mystock_ui.client`.
2. The **backend is the only writer and reader** of `/data/mystock.duckdb`.
3. Secrets (API keys, the inter-service API key, TLS paths) come from environment variables (`.env`) and are never stored in source.
4. **The calculation code is carried over as-is, not rewritten.** The legacy math classes and functions are copied with only package/import changes:
   * Java: `Dates`, `TimeSeries*`, `Transforms`, `Price`, `BSOption`, `Corls`, `StockFeature`, `StockInfo`, `StockFeat`, `StockModel`, `ICBackTest`, `ICStrategies`, and the metric logic in `StockReportSvc`.
   * Python: `option.py`, `optionprice.py`, `positions.py`, `plots.py`, and the calculation parts of `myutils.py`, `myglobal.py` and `utils.py`.

   Only the boundaries change:
   * DuckDB-backed adapters keep the legacy data-access method signatures and units, so the math sees exactly what it saw before.
   * On the Python side, a drop-in replacement for `svcreader.py` calls the new socket API.
   * Output goes to DuckDB tables or Arrow instead of CSV files.

   Because the legacy math relies on static mutable state, the backend runs analytics calls one at a time behind a single lock instead of refactoring that state. Golden tests check that the new outputs equal the legacy outputs.
5. **Nothing is pruned.** Every legacy Python function and class is kept, including those used only from IPython and never from the Streamlit app. `mystock_ui.legacy.shell` recreates the old IPython startup (`ctx`, `ss`, `q`, `feat`, `ssf`, `ssq`, …) on top of the new API.

### Data model
DuckDB schema: `backend/src/main/resources/schema.sql`. The legacy-to-new mapping:

| Legacy | New table |
|---|---|
| `usstocks.db: stocks` | `symbols` |
| `usstocks.db: stocks_status` | `price_status` |
| `eodmktpx.db: quotes` (int cents) | `prices` (DOUBLE dollars, DATE) |
| `stockvol.db: stockvol` | `stock_vol` |
| `oxeodmktpx.db: quotes` | `option_quotes` |
| `oxeodmktpx.db: stockfeatures` | `stock_features` |
| `financials.db: pnlstmt` | `financials` (long format) |
| `trades.db: login` | `users` (PBKDF2 hashes) + `sessions` |
| `trades.db: trades_log` | `trades` |
| `out.csv` | `stock_metrics` |
| `port.csv` | `portfolio_metrics` (+ `portfolio_series`) |
| `train.csv` / `pred.csv` | `model_training` / `model_features` |
| `prediction.csv` | `predictions` |
| `queries.csv`, `dynamic_reports.csv` | `saved_queries`, `dynamic_reports` |

`MIGRATE_SQLITE` uses DuckDB's sqlite scanner to import an existing legacy `/data` directory. You keep the full price history without re-downloading it from Alpha Vantage.
