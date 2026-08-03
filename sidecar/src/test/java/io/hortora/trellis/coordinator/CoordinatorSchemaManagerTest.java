package io.hortora.trellis.coordinator;

import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatorSchemaManagerTest {

    @Test
    void schemaCreatedOnInitialize() throws Exception {
        var dbFile = java.nio.file.Files.createTempFile("coord-test-", ".db");
        try {
            var ds      = createDataSource(dbFile);
            var manager = new CoordinatorSchemaManager();
            manager.initialize(ds);

            try (var conn = ds.getConnection();
                 var rs = conn.createStatement()
                              .executeQuery("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
                var tables = new ArrayList<String>();
                while (rs.next()) {tables.add(rs.getString(1));}
                assertTrue(tables.contains("coordinator_conversations"), "conversations table missing, got: " + tables);
                assertTrue(tables.contains("coordinator_advice"), "advice table missing");
                assertTrue(tables.contains("coordinator_cases"), "cases table missing");
            }
        } finally {
            java.nio.file.Files.deleteIfExists(dbFile);
        }
    }

    @Test
    void initializeIsIdempotent() throws Exception {
        var dbFile = java.nio.file.Files.createTempFile("coord-test-", ".db");
        try {
            var ds      = createDataSource(dbFile);
            var manager = new CoordinatorSchemaManager();
            manager.initialize(ds);
            assertDoesNotThrow(() -> manager.initialize(ds));
        } finally {
            java.nio.file.Files.deleteIfExists(dbFile);
        }
    }

    @Test
    void versionSetAfterInitialize() throws Exception {
        var dbFile = java.nio.file.Files.createTempFile("coord-test-", ".db");
        try {
            var ds      = createDataSource(dbFile);
            var manager = new CoordinatorSchemaManager();
            manager.initialize(ds);

            try (var conn = ds.getConnection();
                 var rs = conn.createStatement().executeQuery("PRAGMA user_version")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
        } finally {
            java.nio.file.Files.deleteIfExists(dbFile);
        }
    }

    @Test
    void schemaV2CreatesActionsTable() throws Exception {
        var dbFile = java.nio.file.Files.createTempFile("coord-test-", ".db");
        try {
            var ds = createDataSource(dbFile);
            new CoordinatorSchemaManager().initialize(ds);
            try (var conn = ds.getConnection();
                 var rs = conn.createStatement().executeQuery(
                         "SELECT name FROM sqlite_master WHERE type='table' AND name='coordinator_actions'")) {
                assertTrue(rs.next(), "coordinator_actions table should exist");
            }
        } finally {
            java.nio.file.Files.deleteIfExists(dbFile);
        }
    }


    private SQLiteDataSource createDataSource(java.nio.file.Path dbFile) {
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        return ds;
    }
}
