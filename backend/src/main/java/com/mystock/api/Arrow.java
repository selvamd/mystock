package com.mystock.api;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.*;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Arrow IPC stream bodies: from DuckDB queries (zero-copy export) or from computed Java columns. */
public final class Arrow {
    public static final BufferAllocator ALLOCATOR = new RootAllocator();

    private Arrow() {}

    /** A response body written after the header frame. {@link #prepare()} runs before the header is sent. */
    public interface Body extends AutoCloseable {
        default void prepare() throws Exception {}
        void write(OutputStream out) throws Exception;
        default Long rows() { return null; }
        @Override default void close() {}
    }

    /** Wraps the socket stream so that closing the Arrow writer does not close the socket. */
    static OutputStream nonClosing(OutputStream out) {
        return new FilterOutputStream(out) {
            @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
            @Override public void close() throws IOException { out.flush(); }
        };
    }

    // ------------------------------------------------------------------ SQL
    public static Body sql(String sql, Object... params) {
        return new SqlBody(sql, params);
    }

    static final class SqlBody implements Body {
        private final String sql;
        private final Object[] params;
        private Connection conn;
        private PreparedStatement ps;
        private ResultSet rs;

        SqlBody(String sql, Object[] params) { this.sql = sql; this.params = params; }

        @Override public void prepare() throws Exception {
            conn = com.mystock.db.Database.current().connect();
            ps = conn.prepareStatement(sql);
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p instanceof LocalDate d) ps.setDate(i + 1, java.sql.Date.valueOf(d));
                else ps.setObject(i + 1, p);
            }
            rs = ps.executeQuery();
        }

        @Override public void write(OutputStream out) throws Exception {
            try (ArrowReader reader = (ArrowReader) ((org.duckdb.DuckDBResultSet) rs).arrowExportStream(ALLOCATOR, 65536)) {
                VectorSchemaRoot root = reader.getVectorSchemaRoot();
                try (ArrowStreamWriter w = new ArrowStreamWriter(root, null, nonClosing(out))) {
                    w.start();
                    while (reader.loadNextBatch()) w.writeBatch();
                    w.end();
                }
            }
        }

        @Override public void close() {
            try { if (rs != null) rs.close(); } catch (Exception ignored) { }
            try { if (ps != null) ps.close(); } catch (Exception ignored) { }
            try { if (conn != null) conn.close(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ computed tables
    public enum Type { DATE, INT, LONG, DOUBLE, STRING, TIMESTAMP }

    /** Column-oriented builder for computed results. DATE values are LocalDate or yyyymmdd Integer. */
    public static final class Table implements Body {
        private final List<String> names = new ArrayList<>();
        private final List<Type> types = new ArrayList<>();
        private final List<Object[]> rows = new ArrayList<>();

        public Table col(String name, Type t) { names.add(name); types.add(t); return this; }

        public Table row(Object... values) {
            if (values.length != names.size()) throw new IllegalArgumentException("row width " + values.length + " != " + names.size());
            rows.add(values);
            return this;
        }

        public int size() { return rows.size(); }

        @Override public Long rows() { return (long) rows.size(); }

        private static ArrowType arrowType(Type t) {
            return switch (t) {
                case DATE -> new ArrowType.Date(DateUnit.DAY);
                case INT -> new ArrowType.Int(32, true);
                case LONG -> new ArrowType.Int(64, true);
                case DOUBLE -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
                case STRING -> new ArrowType.Utf8();
                case TIMESTAMP -> new ArrowType.Timestamp(org.apache.arrow.vector.types.TimeUnit.MICROSECOND, null);
            };
        }

        @Override public void write(OutputStream out) throws Exception {
            List<Field> fields = new ArrayList<>();
            for (int i = 0; i < names.size(); i++)
                fields.add(new Field(names.get(i), FieldType.nullable(arrowType(types.get(i))), null));
            try (VectorSchemaRoot root = VectorSchemaRoot.create(new Schema(fields), ALLOCATOR);
                 ArrowStreamWriter w = new ArrowStreamWriter(root, null, nonClosing(out))) {
                w.start();
                int batch = 65536;
                for (int start = 0; start < rows.size(); start += batch) {
                    int n = Math.min(batch, rows.size() - start);
                    root.allocateNew();
                    for (int c = 0; c < names.size(); c++) {
                        FieldVector v = root.getVector(c);
                        for (int r = 0; r < n; r++) set(v, types.get(c), r, rows.get(start + r)[c]);
                        v.setValueCount(n);
                    }
                    root.setRowCount(n);
                    w.writeBatch();
                }
                w.end();
            }
        }

        private static void set(FieldVector v, Type t, int r, Object o) {
            if (o == null) { setNull(v, r); return; }
            switch (t) {
                case DATE -> {
                    LocalDate d = (o instanceof LocalDate ld) ? ld : ymd(((Number) o).intValue());
                    ((DateDayVector) v).setSafe(r, (int) d.toEpochDay());
                }
                case INT -> ((IntVector) v).setSafe(r, ((Number) o).intValue());
                case LONG -> ((BigIntVector) v).setSafe(r, ((Number) o).longValue());
                case DOUBLE -> ((Float8Vector) v).setSafe(r, ((Number) o).doubleValue());
                case STRING -> ((VarCharVector) v).setSafe(r, o.toString().getBytes(StandardCharsets.UTF_8));
                case TIMESTAMP -> ((TimeStampMicroVector) v).setSafe(r, ((Number) o).longValue());
            }
        }

        private static void setNull(FieldVector v, int r) {
            if (v instanceof BaseFixedWidthVector f) f.setNull(r);
            else if (v instanceof BaseVariableWidthVector b) b.setNull(r);
        }
    }

    public static LocalDate ymd(int dt) {
        return LocalDate.of(dt / 10000, (dt / 100) % 100, dt % 100);
    }
}
