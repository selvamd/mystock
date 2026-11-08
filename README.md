# mystock

Stock and options analytics platform: a Java/DuckDB data backend and a Plotly Dash frontend that talk over a socket API (Arrow IPC).

This is a full rewrite of the legacy app in [`mystk/`](mystk/) (Java + SQLite + Streamlit), which is kept unchanged for reference.

* [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): assessment of the legacy system, target design, legacy-to-new data mapping
* [docs/PROTOCOL.md](docs/PROTOCOL.md): socket protocol and API operations (the contract between the tiers)
* [backend/README.md](backend/README.md): Java backend (jobs, analytics, socket server, CLI)
* [frontend/README.md](frontend/README.md): Dash frontend

```
mystock/
├── backend/     Java 21, Maven: DuckDB owner, ETL jobs, analytics, socket API (:9090)
├── frontend/    Python, Plotly Dash: UI (:8443), talks only to the backend API
├── docs/        architecture + protocol contract
├── docker-compose.yml
├── .env.example
└── mystk/       legacy code (read-only reference)
```

## Quick start (Docker)

```bash
cp .env.example .env             # set MYSTOCK_API_KEY, MYSTOCK_UI_SECRET, provider API keys
mkdir -p data

# Option A: offline demo dataset (no API keys needed), creates user demo/demo
docker compose run --rm backend seed-demo

# Option B: import an existing legacy /data directory (SQLite files + CSVs)
docker compose run --rm -v /path/to/legacy/data:/legacy:ro backend migrate /legacy
docker compose run --rm backend adduser <login> <password>

docker compose up -d --build
open https://localhost:8443      # or http:// if no TLS cert is configured
```

Option B keeps your full price history without re-downloading it from Alpha Vantage.

The backend runs the daily EOD pipeline (symbols → prices → KPI snapshots) on its own schedule. To trigger a job on demand, use the `admin.run_job` API op. To run a job as a one-off, stop the server first and run `docker compose run --rm backend task <JOB>`.

## Key design decisions

* **One backend process owns DuckDB.** DuckDB allows a single read-write process per database file. The legacy split of "loader" and "API server" containers would deadlock on the file lock, so ETL now runs on a scheduler inside the API server.
* **Arrow over the socket.** DuckDB result sets stream to the client as Arrow IPC and become pandas DataFrames without CSV parsing. Requests are length-prefixed JSON.
* **Strict tiering.** The frontend has no database drivers and no third-party API keys. Alpha Vantage, Polygon, Yahoo, Finnhub and CBOE are called only by the backend.
* **Faithful quant port.** Metric formulas, windows and calendar-day semantics match the legacy `TimeSeries` code, so the numbers stay comparable. Known quirks are marked `LEGACY:` in the code rather than silently changed.
