package com.mystock.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Typed access to request {@code args}; every problem is a BAD_REQUEST. */
public final class Args {
    private final JsonObject o;

    public Args(JsonObject o) { this.o = (o == null) ? new JsonObject() : o; }

    public boolean has(String k) { return o.has(k) && !o.get(k).isJsonNull(); }

    public String str(String k) {
        if (!has(k)) throw ApiException.badRequest("missing argument '" + k + "'");
        JsonElement e = o.get(k);
        if (!e.isJsonPrimitive()) throw ApiException.badRequest("argument '" + k + "' must be a scalar");
        return e.getAsString();
    }

    public String optStr(String k, String def) { return has(k) ? str(k) : def; }

    public String ticker(String k) { return str(k).trim().toUpperCase(Locale.ROOT); }

    public double dbl(String k) {
        try { return o.get(k).getAsDouble(); }
        catch (Exception e) {
            if (!has(k)) throw ApiException.badRequest("missing argument '" + k + "'");
            throw ApiException.badRequest("argument '" + k + "' must be a number");
        }
    }

    public Double optDbl(String k) { return has(k) ? dbl(k) : null; }

    public int integer(String k) {
        try { return o.get(k).getAsInt(); }
        catch (Exception e) {
            if (!has(k)) throw ApiException.badRequest("missing argument '" + k + "'");
            throw ApiException.badRequest("argument '" + k + "' must be an integer");
        }
    }

    public Integer optInt(String k) { return has(k) ? integer(k) : null; }

    public boolean bool(String k, boolean def) {
        if (!has(k)) return def;
        JsonElement e = o.get(k);
        try { return e.getAsBoolean(); } catch (Exception ex) { throw ApiException.badRequest("argument '" + k + "' must be a boolean"); }
    }

    /** ISO "YYYY-MM-DD" or legacy yyyymmdd (number or string). Returns yyyymmdd. */
    public int date(String k) {
        String s = str(k).trim();
        try {
            if (s.matches("\\d{8}")) {
                int v = Integer.parseInt(s);
                LocalDate.of(v / 10000, (v / 100) % 100, v % 100);
                return v;
            }
            LocalDate d = LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
            return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
        } catch (java.time.DateTimeException | NumberFormatException e) {
            throw ApiException.badRequest("argument '" + k + "' is not a date: " + s);
        }
    }

    public Integer optDate(String k) { return has(k) ? date(k) : null; }

    public List<String> list(String k) {
        if (!has(k)) throw ApiException.badRequest("missing argument '" + k + "'");
        JsonElement e = o.get(k);
        List<String> out = new ArrayList<>();
        if (e.isJsonArray()) {
            for (JsonElement x : (JsonArray) e) out.add(x.getAsString());
        } else {
            for (String s : e.getAsString().split(",")) if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }
}
