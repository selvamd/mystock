package com.mystock.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mystock.ingest.UpstreamException;
import com.mystock.legacy.Legacy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Socket server implementing docs/PROTOCOL.md: uint32-BE length-prefixed frames, JSON requests,
 * header frame + Arrow IPC stream / JSON frame responses. One virtual thread per persistent connection.
 */
public final class Server implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(Server.class);
    public static final int MAX_REQUEST = 1 << 20;
    static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private final byte[] apiKey;
    private final Ops ops;
    private final ServerSocket socket;
    private volatile boolean running = true;
    private Thread acceptThread;

    public Server(int port, String apiKey, Ops ops) throws IOException {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("MYSTOCK_API_KEY must be set");
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.ops = ops;
        this.socket = new ServerSocket(port);
    }

    public int port() { return socket.getLocalPort(); }

    public void start() {
        acceptThread = Thread.ofPlatform().name("api-accept").start(this::acceptLoop);
        log.info("API listening on port {}", port());
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = socket.accept();
                Thread.ofVirtual().name("conn-" + s.getRemoteSocketAddress()).start(() -> handle(s));
            } catch (IOException e) {
                if (running) log.warn("accept failed: {}", e.toString());
            }
        }
    }

    private void handle(Socket s) {
        try (s; DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
             OutputStream raw = new BufferedOutputStream(s.getOutputStream(), 1 << 16)) {
            s.setTcpNoDelay(true);
            while (running) {
                int len;
                try {
                    len = in.readInt();
                } catch (EOFException eof) {
                    return;
                }
                if (len < 0 || len > MAX_REQUEST) {
                    writeError(raw, null, "BAD_REQUEST", "request frame too large (max 1 MB)");
                    return;
                }
                byte[] payload = new byte[len];
                in.readFully(payload);
                if (!process(payload, raw)) return;
            }
        } catch (SocketException | EOFException e) {
            // client went away
        } catch (Exception e) {
            log.warn("connection error: {}", e.toString());
        }
    }

    /** Processes one request; returns false if the connection must be closed. */
    private boolean process(byte[] payload, OutputStream out) throws IOException {
        long t0 = System.nanoTime();
        String id = null, op = "?";
        String status = "ok";
        JsonObject req;
        try {
            JsonElement el = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8));
            if (!el.isJsonObject()) throw new IllegalStateException("not an object");
            req = el.getAsJsonObject();
        } catch (Exception e) {
            writeError(out, null, "BAD_REQUEST", "request is not valid JSON");
            log.info("op=? status=BAD_REQUEST (invalid json)");
            return true;
        }
        try {
            if (req.has("id") && !req.get("id").isJsonNull()) id = req.get("id").getAsString();
            if (req.has("op") && !req.get("op").isJsonNull()) op = req.get("op").getAsString();
            String key = req.has("key") && !req.get("key").isJsonNull() ? req.get("key").getAsString() : "";
            if (!MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8))) {
                writeError(out, id, "UNAUTHORIZED", "invalid API key");
                log.info("op={} status=UNAUTHORIZED", op);
                return false;
            }
            if (req.has("v") && !req.get("v").isJsonNull()) {
                int v;
                try { v = (int) Math.floor(req.get("v").getAsDouble()); } catch (Exception e) { v = -1; }
                if (v != 1) throw ApiException.badRequest("unsupported protocol version " + req.get("v"));
            }
            JsonObject args = req.has("args") && req.get("args").isJsonObject() ? req.getAsJsonObject("args") : new JsonObject();
            Reply reply = ops.dispatch(op, new Args(args));
            writeReply(out, id, reply);
        } catch (StreamBroken e) {
            status = "BROKEN";
            log.error("op={} failed while streaming", op, e.getCause());
            return false;
        } catch (Throwable e) {
            String[] ce = classify(e);
            status = ce[0];
            if (status.equals("INTERNAL")) log.error("op={} failed", op, e);
            writeError(out, id, ce[0], ce[1]);
        } finally {
            log.info("op={} status={} ms={}", op, status, (System.nanoTime() - t0) / 1_000_000);
        }
        return true;
    }

    static String[] classify(Throwable e) {
        if (e instanceof ApiException a) return new String[]{a.code, a.getMessage()};
        if (e instanceof UpstreamException) return new String[]{"UPSTREAM", e.getMessage()};
        if (e instanceof Legacy.NoMarketData || e instanceof Legacy.NotInUniverse) return new String[]{"NOT_FOUND", e.getMessage()};
        if (e instanceof IllegalArgumentException) return new String[]{"BAD_REQUEST", String.valueOf(e.getMessage())};
        return new String[]{"INTERNAL", e.getClass().getSimpleName() + ": " + e.getMessage()};
    }

    private void writeReply(OutputStream out, String id, Reply reply) throws Exception {
        Map<String, Object> h = header(id, "ok");
        if (reply.arrow != null) {
            try (Arrow.Body body = reply.arrow) {
                body.prepare(); // run the query before committing to "ok"
                h.put("format", "arrow");
                if (body.rows() != null) h.put("rows", body.rows());
                writeFrame(out, GSON.toJson(h));
                try {
                    body.write(out);
                    out.flush();
                } catch (Exception e) {
                    // header already sent: the stream is unusable, close the connection
                    throw new StreamBroken(e);
                }
            }
        } else if (reply.json != null) {
            h.put("format", "json");
            writeFrame(out, GSON.toJson(h));
            writeFrame(out, GSON.toJson(reply.json));
            out.flush();
        } else {
            h.put("format", "none");
            writeFrame(out, GSON.toJson(h));
            out.flush();
        }
    }

    private static Map<String, Object> header(String id, String status) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("v", 1);
        h.put("id", id);
        h.put("status", status);
        return h;
    }

    private static void writeError(OutputStream out, String id, String code, String msg) throws IOException {
        Map<String, Object> h = header(id, "error");
        h.put("format", "none");
        h.put("code", code);
        h.put("error", msg);
        writeFrame(out, GSON.toJson(h));
        out.flush();
    }

    static void writeFrame(OutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(new byte[]{(byte) (b.length >>> 24), (byte) (b.length >>> 16), (byte) (b.length >>> 8), (byte) b.length});
        out.write(b);
    }

    static final class StreamBroken extends IOException {
        StreamBroken(Throwable c) { super(c); }
    }

    @Override
    public void close() {
        running = false;
        try { socket.close(); } catch (IOException ignored) { }
    }
}
