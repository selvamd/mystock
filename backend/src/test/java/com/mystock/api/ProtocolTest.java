package com.mystock.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mystock.Fixture;
import com.mystock.jobs.JobRunner;
import com.mystock.legacy.Legacy;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Starts the socket server on an ephemeral port against the seeded temp DB and speaks the wire protocol. */
class ProtocolTest {
    static Server server;
    static final Gson GSON = new Gson();

    @BeforeAll
    static void start() throws Exception {
        Fixture.seeded();
        Legacy.reload();
        server = new Server(0, "test-key", new Ops(new JobRunner()));
        server.start();
    }

    @AfterAll
    static void stop() { server.close(); }

    static final class Client implements Closeable {
        final Socket s;
        final DataInputStream in;
        final OutputStream out;
        int n;

        Client() throws IOException {
            s = new Socket("127.0.0.1", server.port());
            in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out = s.getOutputStream();
        }

        JsonObject send(String key, String op, Map<String, Object> args) throws IOException {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("v", 1);
            req.put("id", "t" + (++n));
            req.put("key", key);
            req.put("op", op);
            req.put("args", args);
            byte[] b = GSON.toJson(req).getBytes(StandardCharsets.UTF_8);
            DataOutputStream d = new DataOutputStream(out);
            d.writeInt(b.length);
            d.write(b);
            d.flush();
            JsonObject h = JsonParser.parseString(frame()).getAsJsonObject();
            assertEquals("t" + n, h.get("id").getAsString());
            return h;
        }

        String frame() throws IOException {
            int len = in.readInt();
            byte[] b = new byte[len];
            in.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        }

        /** Reads an Arrow stream body: column names, types and row count. */
        Object[] arrow() throws IOException {
            try (ArrowStreamReader r = new ArrowStreamReader(in, Arrow.ALLOCATOR) {
                @Override public void close() { /* keep socket open */ }
            }) {
                VectorSchemaRoot root = r.getVectorSchemaRoot();
                List<Field> fields = root.getSchema().getFields();
                long rows = 0;
                while (r.loadNextBatch()) rows += root.getRowCount();
                List<String> names = new ArrayList<>();
                for (Field f : fields) names.add(f.getName());
                return new Object[]{names, fields, rows};
            }
        }

        @Override public void close() throws IOException { s.close(); }
    }

    @SuppressWarnings("unchecked")
    @Test
    void roundTrips() throws Exception {
        try (Client c = new Client()) {
            JsonObject h = c.send("test-key", "ping", Map.of());
            assertEquals("ok", h.get("status").getAsString());
            assertEquals("json", h.get("format").getAsString());
            JsonObject body = JsonParser.parseString(c.frame()).getAsJsonObject();
            assertTrue(body.get("pong").getAsBoolean());
            assertEquals("2025-06-30", body.get("db_date").getAsString());

            h = c.send("test-key", "screener.stocks", Map.of());
            assertEquals("arrow", h.get("format").getAsString());
            Object[] a = c.arrow();
            List<String> names = (List<String>) a[0];
            assertEquals("asof", names.get(0));
            assertEquals(List.of("summ", "impvol", "earnings", "income", "revenue"), names.subList(names.size() - 5, names.size()));
            assertEquals(85, names.size());
            for (Field f : (List<Field>) a[1]) assertFalse(f.getType() instanceof ArrowType.Decimal, f.getName());
            assertTrue((Long) a[2] > 40);

            h = c.send("test-key", "prices.ohlc", Map.of("ticker", "nvda", "start", "2024-06-01", "end", 20240630));
            a = c.arrow();
            assertEquals(List.of("asof", "open", "high", "low", "close", "prev", "volume"), a[0]);
            assertTrue((Long) a[2] >= 20);

            h = c.send("test-key", "metrics.series", Map.of("ticker", "SPY", "metric", "RSI"));
            a = c.arrow();
            assertEquals(List.of("asof", "value"), a[0]);
            assertTrue(((List<Field>) a[1]).get(0).getType() instanceof ArrowType.Date);

            h = c.send("test-key", "metrics.series", Map.of("ticker", "SPY", "metric", "OPEN"));
            a = c.arrow();
            assertEquals(0L, a[2], "metrics without legacy implementation return an empty stream");

            h = c.send("test-key", "prices.history", Map.of("ticker", "ZZZZ"));
            assertEquals("error", h.get("status").getAsString());
            assertEquals("NOT_FOUND", h.get("code").getAsString());
            assertEquals("none", h.get("format").getAsString());

            h = c.send("test-key", "no.such.op", Map.of());
            assertEquals("BAD_REQUEST", h.get("code").getAsString());

            h = c.send("test-key", "trades.list", Map.of("token", "nope"));
            assertEquals("FORBIDDEN", h.get("code").getAsString());

            h = c.send("test-key", "auth.login", Map.of("login", "demo", "password", "demo"));
            String token = JsonParser.parseString(c.frame()).getAsJsonObject().get("token").getAsString();
            h = c.send("test-key", "trades.book", Map.of("token", token, "portfolio", "p", "position", "x", "buysell", "B",
                    "legsym", "SPY@500@251219@C", "px", 1.0, "qty", 2, "upx", 600.0, "uvol", 0.2));
            assertEquals("ok", h.get("status").getAsString());
            c.frame();
            h = c.send("test-key", "trades.list", Map.of("token", token));
            a = c.arrow();
            assertEquals(1L, a[2]);
            h = c.send("test-key", "trades.book", Map.of("token", token, "portfolio", "p", "position", "x", "buysell", "B",
                    "legsym", "SPY@500@251219@C", "px", 1.0, "qty", 0));
            c.frame();
            h = c.send("test-key", "trades.list", Map.of("token", token));
            a = c.arrow();
            assertEquals(0L, a[2], "qty 0 deletes the leg");

            h = c.send("test-key", "backtest.shadow", Map.of("ticker", "SPY", "initdt", "2025-05-01"));
            a = c.arrow();
            assertEquals("ymd", ((List<String>) a[0]).get(0));
            assertEquals(15, ((List<String>) a[0]).size());

            h = c.send("wrong", "ping", Map.of());
            assertEquals("UNAUTHORIZED", h.get("code").getAsString());
            assertThrows(IOException.class, () -> c.in.readInt(), "connection must be closed");
        }
    }

    @Test
    void oversizedFrameRejected() throws Exception {
        try (Client c = new Client()) {
            DataOutputStream d = new DataOutputStream(c.out);
            d.writeInt(Server.MAX_REQUEST + 1);
            d.flush();
            JsonObject h = JsonParser.parseString(c.frame()).getAsJsonObject();
            assertEquals("BAD_REQUEST", h.get("code").getAsString());
        }
    }
}
