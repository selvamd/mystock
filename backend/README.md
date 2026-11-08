# mystock backend

Java 21 service that owns `${MYSTOCK_DATA_DIR}/mystock.duckdb`, runs the ETL jobs, and serves the socket API
described in [`docs/PROTOCOL.md`](../docs/PROTOCOL.md). See [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) for the design.

## Layout

| Package | Contents |
|---|---|
| `com.mystock.legacy` | The legacy analytics, **copied verbatim** from `mystk/java` (Dates, TimeSeriesData, TimeSeries, Transforms, Price, BSOption, Corls, StockFeature, Const, Context, StockInfo, StockFeat, StockModel, ICBackTest, ICStrategies, ICMonitor, StockAggFeature, StockOxParser, StockFeatureDB, and the METRIC/PRICE/OHLC/SHADOW half of StockReportSvc). Only a `package` line was added. The few changes are marked `CHANGED`, `ADAPTER` or `FIX` in the source and listed below. |
| `com.mystock.legacy` (adapters) | DuckDB-backed `StockDB`, `PriceDB`, `StockStatusDB`, `StockVolDB`, `StockFinDB`, `OptionPriceDB` (and stubs `LoginDB`, `TradesDB`) with the **same method signatures and units** the legacy math expects: prices come back as int cents, dates as `yyyymmdd`, and symbols are addressed by symid. |
| `com.mystock.legacy.Legacy` | The only entry point from new code into the legacy statics. It holds one global fair lock, so the analytics run single-threaded. |
| `api` | Socket server (virtual thread per connection), Arrow IPC writer, op registry (`Ops`). |
| `jobs` | `JobRunner` (single-threaded, `job_runs`), CSV loaders (legacy `out.csv`/`port.csv`/`pred.csv`/`train.csv` → tables), EOD `Scheduler`, `VolLoop`. |
| `ingest` | Shared HTTPS client, Alpha Vantage token-bucket rate limiter, throttle-note detection, API keys from env. |
| `services` | Auth (PBKDF2 + sessions), CBOE VIX/SKEW + Polygon news caches, Alpha Vantage financials. |
| `tools` | `SeedDemo`, `Migrate`. |

## Build & test

```bash
mvn -q -f backend/pom.xml verify          # compiles for release 21, runs the tests, builds target/mystock-backend.jar
```

The tests include **golden tests against the original legacy code**. The legacy sources in `mystk/java` were compiled
with `javac` and run, using SQLite, on the same deterministic dataset (`src/test/java/com/mystock/golden/GoldenExport.java`
exports it in the legacy SQLite layout). Their outputs are stored in `src/test/resources/golden/`, and the copied classes,
running on DuckDB through the adapters, must reproduce them exactly. This covers `out.csv`, `port.csv`, all 35 METRIC series
for SPY and AAPL, OHLC, PRICE, both SHADOW back-tests, OPTFEAT stock features, and a bit-exact probe of
Dates/TimeSeries/Transforms/BSOption/Corls.

## Run

```bash
cd backend
export MYSTOCK_DATA_DIR=$PWD/../data MYSTOCK_API_KEY=change-me
./run.sh seed-demo                        # offline demo dataset, also runs STOCKINFO + STOCKFEAT
./run.sh server                           # API on :9090, EOD scheduler, vol loop
python3 -I scripts/smoke.py 127.0.0.1 9090 change-me   # exercises every op in PROTOCOL.md
```

`run.sh` adds `--add-opens=java.base/java.nio=ALL-UNNAMED`, which Arrow needs. Use it, or pass the flag yourself.
The demo user is `demo` / `demo`.

### CLI

| Command | |
|---|---|
| `server` | Socket server, scheduler and vol loop, all in **one JVM**. DuckDB lets only one process open the file. |
| `task <JOB>` | Runs one job, then exits. The server must be stopped. |
| `migrate <legacyDataDir>` | Imports `usstocks.db`, `eodmktpx.db`, `stockvol.db`, `oxeodmktpx.db`, `financials.db`, `trades.db`, `queries.csv`, `dynamic_reports.csv` and `prediction.csv` when present. Missing files are skipped. Needs network once for `INSTALL sqlite`. Run `task STOCKINFO` and `task STOCKFEAT` afterwards. |
| `adduser <login> <password>` | Creates or resets a user (PBKDF2). |
| `seed-demo` | Loads the deterministic synthetic dataset. **Wipes** the market and user tables first. |

### Jobs (`admin.run_job` or `task`)

| Job | Legacy equivalent |
|---|---|
| `SYMBOLS` | `TasksRunner.LoadSymbols(false)`: downloads the NASDAQ directory (HTTPS, with the legacy ftp URL as fallback). Only new ETFs are added. |
| `SYMBOLS_FULL` | `LoadSymbols(true)`: also fetches the Alpha Vantage OVERVIEW for new stocks. |
| `PRICES` | `StockParser.execute()`: incremental TIME_SERIES_DAILY_ADJUSTED, with a deep reload when an adjustment is detected. |
| `STOCKINFO` | `StockInfo.execute()` → `out.csv` → `stock_metrics` |
| `STOCKFEAT` | `StockFeat.execute()` → `port.csv` → `portfolio_metrics` (+ `portfolio_series`) |
| `MODEL` | `StockModel.execute(true)` (pred only, as TasksRunner did) → `model_features` |
| `MODEL_TRAIN` (CLI only) | `StockModel.execute(false)` → `model_training` + `model_features`. Slow. |
| `VOL_ONCE` | One pass of the `LoadVol` loop, paced at 2 s per ticker instead of 120 s |
| `OPTIONS` | `StockOxParser.execute()` → `option_quotes` |
| `OPTFEAT` | `StockOxParser.executeFeatures()` → `stock_features` |
| `FINANCIALS` | New. Alpha Vantage INCOME_STATEMENT/BALANCE_SHEET → `financials`, refreshed when older than 7 days. |
| `EOD` | SYMBOLS → PRICES → STOCKINFO → STOCKFEAT. As in legacy, each step runs even if the previous one failed. |

Jobs run one at a time. When a job changes data, the server rebuilds the legacy in-memory context afterwards
(legacy `INIT`: `StockReportSvc.init()`).

### Environment

| Variable | Default | |
|---|---|---|
| `MYSTOCK_API_KEY` | – | Required for `server`. |
| `MYSTOCK_API_PORT` | 9090 | |
| `MYSTOCK_DATA_DIR` | /data | Holds `mystock.duckdb` and `work/` (legacy CSV scratch files). |
| `MYSTOCK_TZ` | America/New_York | JVM default zone. Legacy `Dates.today()` uses it. |
| `MYSTOCK_SCHEDULER_ENABLED`, `MYSTOCK_EOD_TIME` | true, 18:30 | EOD on weekdays |
| `MYSTOCK_VOL_LOOP_ENABLED`, `MYSTOCK_VOL_LOOP_SLEEP_SECONDS` | true, 120 | |
| `ALPHAVANTAGE_API_KEY`, `ALPHAVANTAGE_CALLS_PER_MINUTE` | –, 75 | |
| `POLYGON_API_KEY`, `FINNHUB_API_KEY` | – | |
| `MYSTOCK_YAHOO_ENABLED` | true | Extra setting. `false` disables the legacy Yahoo page scrape (PVCache, vol loop). |

If a key is missing, the provider fails with a clear "… not configured" error. The op returns `UPSTREAM`, or the job
fails and records the message. Nothing crashes. With no keys at all, `quote.live` answers from data on hand: last close,
`stock_vol` IV (falling back to `stock_metrics` optvol/histvol), and earnings. `options.chain` and
`fundamentals.statements` serve the cached snapshot. `market.vix` returns an empty stream when CBOE is unreachable.

## Docker

```bash
docker build -t mystock-backend backend
docker run -v $PWD/data:/data -e MYSTOCK_API_KEY=... mystock-backend            # server
docker run -v $PWD/data:/data mystock-backend seed-demo                           # any CLI command
```
