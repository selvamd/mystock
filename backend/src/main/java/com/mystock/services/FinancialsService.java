package com.mystock.services;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mystock.db.Database;
import com.mystock.ingest.Http;
import com.mystock.ingest.UpstreamException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Alpha Vantage INCOME_STATEMENT / BALANCE_SHEET into the long-format `financials` table
 * (replaces frontend utils.finstmt and the selenium-scraped financials.db). Cache TTL 7 days.
 */
public final class FinancialsService {
    public static final Duration TTL = Duration.ofDays(7);

    /** True if the cached statements for ticker are younger than the TTL. */
    public boolean fresh(String ticker) throws SQLException {
        try (Connection c = Database.current().connect();
             PreparedStatement ps = c.prepareStatement("SELECT max(fetched_at) FROM financials WHERE ticker = ?")) {
            ps.setString(1, ticker);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getTimestamp(1) == null) return false;
                return rs.getTimestamp(1).toInstant().isAfter(Instant.now().minus(TTL));
            }
        }
    }

    public boolean hasAny(String ticker) throws SQLException {
        try (Connection c = Database.current().connect();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM financials WHERE ticker = ?")) {
            ps.setString(1, ticker);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1) > 0; }
        }
    }

    /** Fetches both statements and replaces the cached rows. Returns rows stored. */
    public int refresh(String ticker) throws SQLException {
        String t = URLEncoder.encode(ticker, StandardCharsets.UTF_8);
        String inc = Http.get(Http.alphaVantageUrl("function=INCOME_STATEMENT&symbol=" + t));
        String bal = Http.get(Http.alphaVantageUrl("function=BALANCE_SHEET&symbol=" + t));
        if (Http.isThrottleNote(inc) || Http.isThrottleNote(bal)) throw new UpstreamException("Alpha Vantage throttled or returned an error note");
        Timestamp now = Timestamp.from(Instant.now());
        int n = 0;
        try (Connection c = Database.current().connect()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement("DELETE FROM financials WHERE ticker = ?")) {
                del.setString(1, ticker);
                del.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO financials VALUES (?,?,?,?,?,?,?)")) {
                n += add(ps, ticker, "income", inc, now);
                n += add(ps, ticker, "balance", bal, now);
                ps.executeBatch();
            }
            c.commit();
        }
        if (n == 0) throw new UpstreamException("no financial statements returned for " + ticker);
        return n;
    }

    private static int add(PreparedStatement ps, String ticker, String stmt, String body, Timestamp now) throws SQLException {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        int n = 0;
        for (String[] pk : new String[][]{{"annualReports", "annual"}, {"quarterlyReports", "quarterly"}}) {
            if (!root.has(pk[0])) continue;
            for (JsonElement e : root.getAsJsonArray(pk[0])) {
                JsonObject r = e.getAsJsonObject();
                if (!r.has("fiscalDateEnding")) continue;
                LocalDate fd;
                try { fd = LocalDate.parse(r.get("fiscalDateEnding").getAsString()); } catch (Exception ex) { continue; }
                for (String k : r.keySet()) {
                    if (k.equals("fiscalDateEnding") || k.equals("reportedCurrency")) continue;
                    double v;
                    try { v = Double.parseDouble(r.get(k).getAsString()); } catch (Exception ex) { continue; } // "None"
                    ps.setString(1, ticker);
                    ps.setString(2, stmt);
                    ps.setDate(3, Date.valueOf(fd));
                    ps.setString(4, pk[1]);
                    ps.setString(5, k);
                    ps.setDouble(6, v);
                    ps.setTimestamp(7, now);
                    ps.addBatch();
                    n++;
                }
            }
        }
        return n;
    }
}
