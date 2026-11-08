package com.mystock.services;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mystock.ingest.Http;
import com.mystock.ingest.UpstreamException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side replacements for frontend third-party calls: CBOE VIX/SKEW (cboevix) and Polygon news. */
public final class MarketDataService {
    public record VixRow(LocalDate date, double vix, double skew) {}
    public record NewsRow(Instant published, String author, String url, String title, String sentiment, String reasoning) {}

    private record Cached<T>(T value, long at) {}

    private volatile Cached<List<VixRow>> vix;
    private final Map<String, Cached<List<NewsRow>>> news = new ConcurrentHashMap<>();

    /** Legacy cboevix: VIX close inner-joined with SKEW/10 on DATE. Cached 1 hour. */
    public List<VixRow> vix() {
        Cached<List<VixRow>> c = vix;
        if (c != null && System.currentTimeMillis() - c.at < 3_600_000) return c.value;
        Map<String, Double> v = parseCsv(Http.get("https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv"), "CLOSE");
        Map<String, Double> s = parseCsv(Http.get("https://cdn.cboe.com/api/global/us_indices/daily_prices/SKEW_History.csv"), "SKEW");
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        List<VixRow> rows = new ArrayList<>();
        for (Map.Entry<String, Double> e : v.entrySet()) {
            Double sk = s.get(e.getKey());
            if (sk == null) continue;
            try { rows.add(new VixRow(LocalDate.parse(e.getKey(), f), e.getValue(), sk / 10.0)); } catch (Exception ignored) { }
        }
        rows.sort(Comparator.comparing(VixRow::date));
        vix = new Cached<>(rows, System.currentTimeMillis());
        return rows;
    }

    private static Map<String, Double> parseCsv(String body, String col) {
        Map<String, Double> res = new LinkedHashMap<>();
        String[] lines = body.split("\\R");
        if (lines.length < 2) throw new UpstreamException("empty CBOE file");
        String[] head = lines[0].split(",");
        int ci = -1;
        for (int i = 0; i < head.length; i++) if (head[i].trim().equalsIgnoreCase(col)) ci = i;
        if (ci < 0) throw new UpstreamException("CBOE file lacks column " + col);
        for (int i = 1; i < lines.length; i++) {
            String[] p = lines[i].split(",");
            if (p.length <= ci) continue;
            try { res.put(p[0].trim(), Double.parseDouble(p[ci].trim())); } catch (NumberFormatException ignored) { }
        }
        return res;
    }

    /** Legacy sapp.extract(): Polygon news, only items whose insights mention the ticker. Cached 30 min. */
    public List<NewsRow> news(String ticker, int limit) {
        String key = ticker + "/" + limit;
        Cached<List<NewsRow>> c = news.get(key);
        if (c != null && System.currentTimeMillis() - c.at < 1_800_000) return c.value;
        String url = "https://api.polygon.io/v2/reference/news?limit=" + limit + "&ticker="
                + URLEncoder.encode(ticker, StandardCharsets.UTF_8) + "&apiKey=" + Http.polygonKey();
        JsonObject root = JsonParser.parseString(Http.get(url)).getAsJsonObject();
        List<NewsRow> rows = new ArrayList<>();
        if (root.has("results")) {
            for (JsonElement je : root.getAsJsonArray("results")) {
                JsonObject js = je.getAsJsonObject();
                if (!js.has("insights")) continue;
                for (JsonElement ie : (JsonArray) js.get("insights")) {
                    JsonObject ins = ie.getAsJsonObject();
                    if (!ticker.equals(s(ins, "ticker"))) continue;
                    Instant pub = null;
                    try { pub = Instant.parse(s(js, "published_utc")); } catch (Exception ignored) { }
                    rows.add(new NewsRow(pub, s(js, "author"), s(js, "article_url"), s(js, "title"),
                            s(ins, "sentiment"), s(ins, "sentiment_reasoning")));
                }
            }
        }
        news.put(key, new Cached<>(rows, System.currentTimeMillis()));
        return rows;
    }

    private static String s(JsonObject o, String k) {
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null;
    }
}
