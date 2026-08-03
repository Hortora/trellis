package io.hortora.trellis.coordinator;

import jakarta.enterprise.context.ApplicationScoped;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

@ApplicationScoped
public class CoordinatorSchemaManager {

    private static final int SCHEMA_VERSION = 3;

    public void initialize(DataSource ds) throws SQLException {
        try (var conn = ds.getConnection()) {
            try (var s = conn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
            } catch (SQLException ignored) {
            }
            try (var s = conn.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON");
            }
            int version = getCurrentVersion(conn);
            if (version < 1) {
                applySchema(conn);
            }
            if (version < 2) {
                applySchemaV2(conn);
            }
            if (version < 3) {
                applySchemaV3(conn);
            }
            setVersion(conn, SCHEMA_VERSION);
        }}

    private int getCurrentVersion(Connection conn) throws SQLException {
        try (var rs = conn.createStatement().executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void applySchema(Connection conn) throws SQLException {
        try (var is = getClass().getClassLoader().getResourceAsStream("coordinator-schema-v1.sql")) {
            if (is == null) {throw new SQLException("coordinator-schema-v1.sql not found on classpath");}
            var sql = new String(is.readAllBytes());
            for (var stmt : sql.split(";")) {
                var trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    try (var s = conn.createStatement()) {
                        s.execute(trimmed);
                    }
                }
            }
        } catch (IOException e) {
            throw new SQLException("Failed to read schema file", e);
        }
    }

    private void applySchemaV2(Connection conn) throws SQLException {
        try (var is = getClass().getClassLoader().getResourceAsStream("coordinator-schema-v2.sql")) {
            if (is == null) {throw new SQLException("coordinator-schema-v2.sql not found on classpath");}
            var sql = new String(is.readAllBytes());
            for (var stmt : sql.split(";")) {
                var trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    try (var s = conn.createStatement()) {s.execute(trimmed);}
                }
            }
        } catch (IOException e) {
            throw new SQLException("Failed to read schema file", e);
        }
    }

    private void applySchemaV3(Connection conn) throws SQLException {
        try (var is = getClass().getClassLoader().getResourceAsStream("coordinator-schema-v3.sql")) {
            if (is == null) {throw new SQLException("coordinator-schema-v3.sql not found on classpath");}
            var sql = new String(is.readAllBytes());
            for (var stmt : sql.split(";")) {
                var trimmed = stmt.trim();
                if (!trimmed.isEmpty()) {
                    try (var s = conn.createStatement()) {s.execute(trimmed);}
                }
            }
        } catch (IOException e) {
            throw new SQLException("Failed to read schema file", e);
        }
    }


    private void setVersion(Connection conn, int version) throws SQLException {
        try (var s = conn.createStatement()) {
            s.execute("PRAGMA user_version = " + version);
        }
    }
}
