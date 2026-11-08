package com.mystock.jobs;

import com.mystock.config.Config;
import com.mystock.db.Database;
import com.mystock.legacy.Legacy;
import com.mystock.legacy.StockVolDB;
import com.mystock.services.FinancialsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs ETL jobs one at a time (single-threaded executor) and records them in {@code job_runs}.
 * Jobs that change data rebuild the legacy in-memory context afterwards (legacy INIT).
 */
public final class JobRunner implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    public static final List<String> API_JOBS = List.of("SYMBOLS", "SYMBOLS_FULL", "PRICES", "STOCKINFO", "STOCKFEAT",
            "MODEL", "VOL_ONCE", "OPTIONS", "OPTFEAT", "FINANCIALS", "EOD");
    /** Extra jobs available from the CLI only. */
    public static final List<String> CLI_JOBS = List.of("MODEL_TRAIN");

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "job-runner");
        t.setDaemon(true);
        return t;
    });

    public static boolean isJob(String job) { return API_JOBS.contains(job) || CLI_JOBS.contains(job); }

    public Future<?> submit(String job) {
        if (!isJob(job)) throw new IllegalArgumentException("unknown job " + job);
        long id = record(job, "queued", null);
        return exec.submit(() -> runRecorded(job, id));
    }

    /** Runs synchronously in the calling thread (CLI {@code task}). Returns true on success. */
    public boolean runNow(String job) {
        if (!isJob(job)) throw new IllegalArgumentException("unknown job " + job);
        return runRecorded(job, record(job, "queued", null));
    }

    private boolean runRecorded(String job, long id) {
        update(id, "running", null, false);
        long t0 = System.currentTimeMillis();
        try {
            String msg = run(job);
            update(id, "ok", msg, true);
            log.info("job {} ok in {}s: {}", job, (System.currentTimeMillis() - t0) / 1000, msg);
            return true;
        } catch (Throwable e) {
            log.error("job {} failed", job, e);
            update(id, "failed", e.getClass().getSimpleName() + ": " + e.getMessage(), true);
            return false;
        }
    }

    /** Executes a job; returns a short status message. */
    public String run(String job) throws Exception {
        Path work = Config.get().workDir();
        Legacy.setWorkDir(work);
        switch (job) {
            case "SYMBOLS": Legacy.runSymbols(false); return reload("symbols loaded");
            case "SYMBOLS_FULL": Legacy.runSymbols(true); return reload("symbols loaded (full)");
            case "PRICES": Legacy.runPrices(); return reload("prices loaded");
            case "STOCKINFO": {
                Files.deleteIfExists(work.resolve("out.csv"));
                int asof = Legacy.runStockInfo();
                if (asof == 0) throw new IllegalStateException("no market data (RSP prices missing)");
                int n = CsvLoader.loadStockMetrics(work.resolve("out.csv"), ymd(asof));
                return reload(n + " stock_metrics rows as of " + ymd(asof));
            }
            case "STOCKFEAT": {
                Files.deleteIfExists(work.resolve("port.csv"));
                Map<String, Map<String, TreeMap<Integer, Double>>> series = new LinkedHashMap<>();
                int asof = Legacy.runStockFeat(series);
                if (asof == 0) throw new IllegalStateException("no market data (RSP prices missing)");
                int n = CsvLoader.loadPortfolioMetrics(work.resolve("port.csv"), ymd(asof));
                CsvLoader.loadPortfolioSeries(series);
                return reload(n + " portfolio_metrics rows as of " + ymd(asof));
            }
            case "MODEL":
            case "MODEL_TRAIN": {
                boolean train = job.equals("MODEL_TRAIN");
                Files.deleteIfExists(work.resolve("pred.csv"));
                if (train) Files.deleteIfExists(work.resolve("train.csv"));
                int asof = Legacy.runModel(train);
                int n = CsvLoader.loadModel(work.resolve("pred.csv"), train ? work.resolve("train.csv") : null, ymd(asof));
                return n + " model_features rows";
            }
            case "VOL_ONCE": return volOnce();
            case "OPTIONS": Legacy.runOptions(); return "option quotes loaded";
            case "OPTFEAT": Legacy.runOptFeat(); return "stock_features computed";
            case "FINANCIALS": return financials();
            case "EOD": {
                // Legacy TasksRunner EOD: LoadSymbols(false), LoadPrices, ExecStockInfo, ExecStockFeat.
                // As in legacy, each step runs even if the previous one failed.
                StringBuilder sb = new StringBuilder();
                for (String step : List.of("SYMBOLS", "PRICES", "STOCKINFO", "STOCKFEAT")) {
                    try {
                        sb.append(step).append(": ").append(run(step)).append("; ");
                    } catch (Exception e) {
                        log.error("EOD step {} failed", step, e);
                        sb.append(step).append(": FAILED ").append(e.getMessage()).append("; ");
                    }
                }
                return sb.toString();
            }
            default: throw new IllegalArgumentException("unknown job " + job);
        }
    }

    private static String reload(String msg) throws Exception {
        boolean ok = Legacy.reload();
        return msg + (ok ? "" : " (context not loaded: no RSP prices)");
    }

    /** One pass of the legacy LoadVol loop with short pacing (the daemon uses MYSTOCK_VOL_LOOP_SLEEP_SECONDS). */
    private String volOnce() throws Exception {
        StockVolDB db = new StockVolDB();
        int ok = 0, fail = 0, consecutive = 0;
        for (String stk : Legacy.volTickers(db)) {
            try {
                Legacy.volUpdate(db, stk);
                ok++;
                consecutive = 0;
            } catch (Exception e) {
                fail++;
                if (++consecutive >= 5 && ok == 0) throw new IllegalStateException("vol source unreachable: " + e.getMessage());
            }
            Thread.sleep(2000);
        }
        return ok + " updated, " + fail + " failed";
    }

    private String financials() throws Exception {
        FinancialsService fs = new FinancialsService();
        List<String> tickers = new ArrayList<>();
        try (Connection c = Database.current().connect(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT ticker FROM symbols WHERE TRY_CAST(MarketCapitalization AS DOUBLE) > 1e9 ORDER BY ticker")) {
            while (rs.next()) tickers.add(rs.getString(1));
        }
        int ok = 0, skipped = 0, fail = 0;
        for (String t : tickers) {
            if (fs.fresh(t)) { skipped++; continue; }
            try { fs.refresh(t); ok++; }
            catch (com.mystock.ingest.NotConfiguredException e) { throw e; }
            catch (Exception e) { fail++; log.warn("financials {} failed: {}", t, e.getMessage()); }
        }
        return ok + " refreshed, " + skipped + " fresh, " + fail + " failed";
    }

    static LocalDate ymd(int dt) { return LocalDate.of(dt / 10000, (dt / 100) % 100, dt % 100); }

    // ------------------------------------------------------------------ job_runs
    private long record(String job, String status, String msg) {
        try (Connection c = Database.current().connect();
             PreparedStatement ps = c.prepareStatement("INSERT INTO job_runs (job, status, started_at, message) VALUES (?, ?, ?, ?) RETURNING id")) {
            ps.setString(1, job);
            ps.setString(2, status);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.setString(4, msg);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        } catch (SQLException e) {
            log.warn("job_runs insert failed: {}", e.toString());
            return -1;
        }
    }

    private void update(long id, String status, String msg, boolean finished) {
        if (id < 0) return;
        String sql = finished
                ? "UPDATE job_runs SET status = ?, message = ?, finished_at = ? WHERE id = ?"
                : "UPDATE job_runs SET status = ?, message = ?, started_at = ? WHERE id = ?";
        try (Connection c = Database.current().connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setString(2, msg == null ? null : (msg.length() > 2000 ? msg.substring(0, 2000) : msg));
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.setLong(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("job_runs update failed: {}", e.toString());
        }
    }

    @Override
    public void close() { exec.shutdownNow(); }
}
