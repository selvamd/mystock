package com.mystock.db;

import org.duckdb.DuckDBConnection;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Owns the single DuckDB database handle of the process (DuckDB allows only one process to open the file).
 * Every caller gets its own connection via {@link DuckDBConnection#duplicate()}; close it when done.
 */
public final class Database implements AutoCloseable {
    private static volatile Database current;

    private final DuckDBConnection root;
    private final Path file;

    private Database(Path file) throws SQLException {
        this.file = file;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
        } catch (Exception e) {
            throw new SQLException("cannot create data dir for " + file, e);
        }
        Class<?> ignored = org.duckdb.DuckDBDriver.class;
        root = (DuckDBConnection) DriverManager.getConnection("jdbc:duckdb:" + file.toAbsolutePath(), new Properties());
        runSchema();
    }

    /** Opens the database file (creating it and the schema if needed) and makes it the process-wide instance. */
    public static synchronized Database open(Path file) throws SQLException {
        if (current != null) current.close();
        current = new Database(file);
        return current;
    }

    public static Database current() {
        Database d = current;
        if (d == null) throw new IllegalStateException("database not opened");
        return d;
    }

    public Path file() { return file; }

    public Connection connect() throws SQLException {
        return root.duplicate();
    }

    private void runSchema() throws SQLException {
        String sql;
        try (InputStream in = Database.class.getResourceAsStream("/schema.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SQLException("schema.sql missing", e);
        }
        StringBuilder clean = new StringBuilder();
        for (String line : sql.split("\n")) {
            int c = line.indexOf("--");
            clean.append(c >= 0 ? line.substring(0, c) : line).append('\n');
        }
        try (Connection c = connect(); Statement st = c.createStatement()) {
            for (String stmt : clean.toString().split(";")) {
                if (!stmt.isBlank()) st.execute(stmt);
            }
        }
    }

    @Override
    public synchronized void close() {
        try { root.close(); } catch (SQLException ignored) { }
        if (current == this) current = null;
    }
}
