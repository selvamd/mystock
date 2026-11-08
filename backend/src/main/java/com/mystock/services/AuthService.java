package com.mystock.services;

import com.mystock.db.Database;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Users and sessions. Replaces legacy LoginDB (plaintext passwords, session = currentTimeMillis()).
 * Kept legacy behaviour: 24h session lifetime and re-use of an existing valid session on login.
 */
public final class AuthService {
    public static final Duration SESSION_TTL = Duration.ofHours(24);
    private static final int ITERATIONS = 120_000;
    private static final SecureRandom RNG = new SecureRandom();

    public record Session(String token, String login, Instant expiresAt) {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        return "pbkdf2_sha256$" + ITERATIONS + "$" + HexFormat.of().formatHex(salt) + "$" + HexFormat.of().formatHex(pbkdf2(password, salt, ITERATIONS));
    }

    public static boolean verify(String password, String stored) {
        if (stored == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2_sha256")) return false;
        byte[] salt = HexFormat.of().parseHex(p[2]);
        byte[] expect = HexFormat.of().parseHex(p[3]);
        return MessageDigest.isEqual(expect, pbkdf2(password, salt, Integer.parseInt(p[1])));
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    public static String newToken() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public void addUser(String login, String password) throws SQLException {
        try (Connection c = Database.current().connect();
             PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO users (login, pass_hash, created_at) VALUES (?, ?, current_timestamp)")) {
            ps.setString(1, login);
            ps.setString(2, hash(password));
            ps.executeUpdate();
        }
    }

    /** Returns a session, or null if login/password is wrong. */
    public Session login(String login, String password) throws SQLException {
        try (Connection c = Database.current().connect()) {
            String stored = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT pass_hash FROM users WHERE login = ?")) {
                ps.setString(1, login);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) stored = rs.getString(1); }
            }
            if (!verify(password, stored)) return null;
            Instant now = Instant.now();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT token, expires_at FROM sessions WHERE login = ? AND expires_at > ? ORDER BY expires_at DESC LIMIT 1")) {
                ps.setString(1, login);
                ps.setTimestamp(2, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new Session(rs.getString(1), login, rs.getTimestamp(2).toInstant());
                }
            }
            Session s = new Session(newToken(), login, now.plus(SESSION_TTL));
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO sessions (token, login, created_at, expires_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, s.token());
                ps.setString(2, login);
                ps.setTimestamp(3, Timestamp.from(now));
                ps.setTimestamp(4, Timestamp.from(s.expiresAt()));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE expires_at <= ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                ps.executeUpdate();
            }
            return s;
        }
    }

    public void logout(String token) throws SQLException {
        try (Connection c = Database.current().connect(); PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE token = ?")) {
            ps.setString(1, token);
            ps.executeUpdate();
        }
    }

    /** Login of a valid (non-expired) session token, or null. */
    public String user(String token) throws SQLException {
        if (token == null || token.isBlank()) return null;
        try (Connection c = Database.current().connect();
             PreparedStatement ps = c.prepareStatement("SELECT login FROM sessions WHERE token = ? AND expires_at > ?")) {
            ps.setString(1, token);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }
}
