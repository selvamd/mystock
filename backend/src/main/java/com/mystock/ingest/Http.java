package com.mystock.ingest;

import com.mystock.config.Config;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Shared HTTP plumbing for all third-party providers. Every failure surfaces as {@link UpstreamException}
 * (protocol code UPSTREAM). API keys come from {@link Config} only.
 */
public final class Http {
    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile RateLimiter avLimiter;

    private Http() {}

    public static String get(String url) { return get(url, Duration.ofSeconds(60)); }

    public static String get(String url, Duration timeout) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("User-Agent", UA).GET().build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2)
                throw new UpstreamException("HTTP " + resp.statusCode() + " from " + redact(url));
            return resp.body();
        } catch (UpstreamException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("interrupted calling " + redact(url));
        } catch (Exception e) {
            throw new UpstreamException("request failed for " + redact(url) + ": " + e);
        }
    }

    public static InputStream stream(String url) {
        return new ByteArrayInputStream(get(url).getBytes(StandardCharsets.UTF_8));
    }

    /** Never log API keys. */
    public static String redact(String url) {
        return url.replaceAll("(?i)(apikey|apiKey|token)=[^&]*", "$1=***");
    }

    // ------------------------------------------------------------------ Alpha Vantage
    public static String alphaVantageKey() {
        String k = Config.get().alphaVantageKey;
        if (k == null || k.isBlank()) throw new NotConfiguredException("ALPHAVANTAGE_API_KEY");
        return k;
    }

    /** Builds an Alpha Vantage URL ({@code query} without apikey) and waits for a rate-limit token. */
    public static String alphaVantageUrl(String query) {
        String key = alphaVantageKey();
        limiter().acquire();
        return "https://www.alphavantage.co/query?" + query + "&apikey=" + key;
    }

    /** Detects Alpha Vantage throttle/error notes, which arrive as HTTP 200 JSON bodies. */
    public static boolean isThrottleNote(String body) {
        if (body == null) return true;
        String head = body.length() > 300 ? body.substring(0, 300) : body;
        return head.trim().startsWith("{") && (head.contains("\"Note\"") || head.contains("\"Information\"") || head.contains("\"Error Message\""));
    }

    private static RateLimiter limiter() {
        RateLimiter l = avLimiter;
        if (l == null) {
            synchronized (Http.class) {
                if (avLimiter == null) avLimiter = new RateLimiter(Math.max(1, Config.get().alphaVantageCallsPerMinute));
                l = avLimiter;
            }
        }
        return l;
    }

    public static String finnhubKey() {
        String k = Config.get().finnhubKey;
        if (k == null || k.isBlank()) throw new NotConfiguredException("FINNHUB_API_KEY");
        return k;
    }

    public static String polygonKey() {
        String k = Config.get().polygonKey;
        if (k == null || k.isBlank()) throw new NotConfiguredException("POLYGON_API_KEY");
        return k;
    }
}
