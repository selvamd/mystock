package com.mystock.jobs;

import com.mystock.legacy.Legacy;
import com.mystock.legacy.StockVolDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Legacy TasksRunner.LoadVol (the default task of the old stockdb container): forever, for each ticker
 * (new large caps first, then oldest updates first) scrape price/earnings/IV and upsert stock_vol,
 * sleeping MYSTOCK_VOL_LOOP_SLEEP_SECONDS (legacy 120 s) between tickers.
 */
public final class VolLoop implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(VolLoop.class);
    private final int sleepSeconds;
    private volatile boolean running = true;
    private Thread thread;

    public VolLoop(int sleepSeconds) { this.sleepSeconds = Math.max(1, sleepSeconds); }

    public void start() {
        thread = Thread.ofPlatform().daemon(true).name("vol-loop").start(this::loop);
    }

    private void loop() {
        StockVolDB db = new StockVolDB();
        while (running) {
            try {
                if (!Legacy.ready()) { Thread.sleep(sleepSeconds * 1000L); continue; }
                for (String stk : Legacy.volTickers(db)) {
                    if (!running) return;
                    try {
                        Legacy.volUpdate(db, stk);
                    } catch (Exception e) {
                        log.debug("Parse failed for ticker {}: {}", stk, e.toString());
                    }
                    Thread.sleep(sleepSeconds * 1000L);
                }
                Thread.sleep(sleepSeconds * 1000L);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log.warn("vol loop error: {}", e.toString());
                try { Thread.sleep(sleepSeconds * 1000L); } catch (InterruptedException ie) { return; }
            }
        }
    }

    @Override public void close() {
        running = false;
        if (thread != null) thread.interrupt();
    }
}
