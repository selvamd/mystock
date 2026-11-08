package com.mystock.services;

import com.mystock.Fixture;
import com.mystock.db.Database;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    @BeforeAll
    static void setup() throws Exception { Fixture.seeded(); }

    @Test
    void hashing() {
        String h = AuthService.hash("s3cret");
        assertTrue(h.startsWith("pbkdf2_sha256$"));
        assertNotEquals(h, AuthService.hash("s3cret"), "salted");
        assertTrue(AuthService.verify("s3cret", h));
        assertFalse(AuthService.verify("s3cret!", h));
        assertFalse(AuthService.verify("x", "plaintext"));
        assertTrue(AuthService.newToken().matches("[0-9a-f]{32}"));
    }

    @Test
    void loginReuseLogoutAndExpiry() throws Exception {
        AuthService a = new AuthService();
        a.addUser("alice", "pw");
        assertNull(a.login("alice", "bad"));
        assertNull(a.login("nobody", "pw"));
        AuthService.Session s1 = a.login("alice", "pw");
        assertNotNull(s1);
        assertEquals("alice", a.user(s1.token()));
        assertEquals(s1.token(), a.login("alice", "pw").token(), "valid session is reused");
        a.logout(s1.token());
        assertNull(a.user(s1.token()));
        AuthService.Session s2 = a.login("alice", "pw");
        assertNotEquals(s1.token(), s2.token());
        try (Connection c = Database.current().connect();
             PreparedStatement ps = c.prepareStatement("UPDATE sessions SET expires_at = ? WHERE token = ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minusSeconds(1)));
            ps.setString(2, s2.token());
            ps.executeUpdate();
        }
        assertNull(a.user(s2.token()), "expired session rejected");
        assertNotEquals(s2.token(), a.login("alice", "pw").token());
    }
}
