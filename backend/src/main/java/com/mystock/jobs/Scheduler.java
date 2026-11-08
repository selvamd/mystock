package com.mystock.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Submits the EOD pipeline at MYSTOCK_EOD_TIME on weekdays in MYSTOCK_TZ. */
public final class Scheduler implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "eod-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final JobRunner runner;
    private final LocalTime at;
    private final ZoneId zone;

    public Scheduler(JobRunner runner, LocalTime at, ZoneId zone) {
        this.runner = runner;
        this.at = at;
        this.zone = zone;
    }

    public static ZonedDateTime next(ZonedDateTime now, LocalTime at) {
        ZonedDateTime t = now.with(at).withSecond(0).withNano(0);
        if (!t.isAfter(now)) t = t.plusDays(1);
        while (t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY) t = t.plusDays(1);
        return t;
    }

    public void start() { scheduleNext(); }

    private void scheduleNext() {
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime n = next(now, at);
        log.info("next EOD run at {}", n);
        ses.schedule(() -> {
            try { runner.submit("EOD"); } catch (Exception e) { log.error("EOD submit failed", e); }
            scheduleNext();
        }, Duration.between(now, n).toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override public void close() { ses.shutdownNow(); }
}
