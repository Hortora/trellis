package io.hortora.trellis.coordinator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class CaseRecorderTest {

    @TempDir Path tmpDir;
    private DataSource ds;
    private CaseRecorder recorder;

    @BeforeEach
    void setUp() throws SQLException {
        var sqlDs = new SQLiteDataSource();
        sqlDs.setUrl("jdbc:sqlite:" + tmpDir.resolve("test.db"));
        ds = sqlDs;
        new CoordinatorSchemaManager().initialize(ds);
        recorder = CaseRecorder.forTest(ds);
    }

    @Test
    void recordReturnsPositiveId() {
        var task = new CoordinatorTask(
                CoordinatorTask.TaskType.CONVERSATIONAL, 1000, 5, 3, "ws1");
        long id = recorder.record(task, "claude-sonnet-5");
        assertTrue(id > 0, "should return positive case ID");
    }

    @Test
    void recordOutcomeUpdatesCase() throws SQLException {
        var task = new CoordinatorTask(
                CoordinatorTask.TaskType.PROACTIVE_ADVICE, 500, 3, 0, "ws1");
        long id = recorder.record(task, "claude-sonnet-5");
        recorder.recordOutcome(id, "acted_on");

        try (var conn = ds.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT outcome, outcome_at FROM coordinator_cases WHERE id = " + id)) {
            assertTrue(rs.next(), "case should exist");
            assertEquals("acted_on", rs.getString("outcome"));
            assertNotNull(rs.getString("outcome_at"));
        }
    }

    @Test
    void recordPersistsAllFields() throws SQLException {
        var task = new CoordinatorTask(
                CoordinatorTask.TaskType.DIRECTIVE, 2000, 10, 7, "ws-key");
        long id = recorder.record(task, "claude-opus-4");

        try (var conn = ds.getConnection();
             var rs = conn.createStatement().executeQuery(
                     "SELECT * FROM coordinator_cases WHERE id = " + id)) {
            assertTrue(rs.next());
            assertEquals("ws-key", rs.getString("workspace"));
            assertEquals("DIRECTIVE", rs.getString("task_type"));
            assertEquals(2000, rs.getInt("context_tokens"));
            assertEquals(10, rs.getInt("event_count"));
            assertEquals(7, rs.getInt("conv_depth"));
            assertEquals("claude-opus-4", rs.getString("model_used"));
            assertNotNull(rs.getString("created_at"));
            assertNull(rs.getString("outcome"));
        }
    }

    @Test
    void recordFailureIsSafeAndReturnsNegative() {
        var uninitDs = new SQLiteDataSource();
        uninitDs.setUrl("jdbc:sqlite:" + tmpDir.resolve("uninitialized.db"));
        var broken = CaseRecorder.forTest(uninitDs);

        var task = new CoordinatorTask(
                CoordinatorTask.TaskType.CONVERSATIONAL, 1000, 5, 3, "ws1");
        long id = assertDoesNotThrow(() -> broken.record(task, "model"));
        assertEquals(-1, id, "should return -1 on failure");
    }

    @Test
    void recordOutcomeFailureIsSafe() {
        var uninitDs = new SQLiteDataSource();
        uninitDs.setUrl("jdbc:sqlite:" + tmpDir.resolve("uninitialized2.db"));
        var broken = CaseRecorder.forTest(uninitDs);

        assertDoesNotThrow(() -> broken.recordOutcome(999, "dismissed"));
    }
}
