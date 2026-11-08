package com.mystock.config;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

/** All runtime settings, read from environment variables (names as in .env.example). */
public final class Config {
    public final String apiKey;
    public final int apiPort;
    public final Path dataDir;
    public final ZoneId tz;
    public final boolean schedulerEnabled;
    public final LocalTime eodTime;
    public final boolean volLoopEnabled;
    public final int volLoopSleepSeconds;
    public final String alphaVantageKey;
    public final int alphaVantageCallsPerMinute;
    public final String polygonKey;
    public final String finnhubKey;
    /** Optional (not in .env.example): disable the fragile Yahoo scrape entirely. Default true. */
    public final boolean yahooEnabled;

    private static volatile Config current;

    public Config(Map<String, String> env) {
        apiKey = str(env, "MYSTOCK_API_KEY", "");
        apiPort = Integer.parseInt(str(env, "MYSTOCK_API_PORT", "9090"));
        dataDir = Path.of(str(env, "MYSTOCK_DATA_DIR", "/data"));
        tz = ZoneId.of(str(env, "MYSTOCK_TZ", "America/New_York"));
        schedulerEnabled = bool(env, "MYSTOCK_SCHEDULER_ENABLED", true);
        eodTime = LocalTime.parse(str(env, "MYSTOCK_EOD_TIME", "18:30"));
        volLoopEnabled = bool(env, "MYSTOCK_VOL_LOOP_ENABLED", true);
        volLoopSleepSeconds = Integer.parseInt(str(env, "MYSTOCK_VOL_LOOP_SLEEP_SECONDS", "120"));
        alphaVantageKey = str(env, "ALPHAVANTAGE_API_KEY", "");
        alphaVantageCallsPerMinute = Integer.parseInt(str(env, "ALPHAVANTAGE_CALLS_PER_MINUTE", "75"));
        polygonKey = str(env, "POLYGON_API_KEY", "");
        finnhubKey = str(env, "FINNHUB_API_KEY", "");
        yahooEnabled = bool(env, "MYSTOCK_YAHOO_ENABLED", true);
    }

    public static Config fromEnv() { return new Config(System.getenv()); }

    public static void set(Config c) { current = c; }

    public static Config get() {
        Config c = current;
        if (c == null) { c = fromEnv(); current = c; }
        return c;
    }

    public Path dbFile() { return dataDir.resolve("mystock.duckdb"); }

    /** Scratch directory where legacy code writes its CSV outputs (out.csv, port.csv, ...) before loading. */
    public Path workDir() { return dataDir.resolve("work"); }

    private static String str(Map<String, String> env, String k, String def) {
        String v = env.get(k);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static boolean bool(Map<String, String> env, String k, boolean def) {
        String v = env.get(k);
        if (v == null || v.isBlank()) return def;
        return v.trim().equalsIgnoreCase("true") || v.trim().equals("1") || v.trim().equalsIgnoreCase("yes");
    }
}
