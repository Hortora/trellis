package io.hortora.trellis.worklog;

import io.hortora.trellis.mcp.GenerationCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorklogServiceTest {

    @TempDir
    Path tmpDir;

    private WorklogService service;
    private GenerationCounter generation;

    @BeforeEach
    void setUp() throws SQLException {
        var dbPath = tmpDir.resolve("test-worklog.db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath);

        try (var conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            createSchema(conn);
            seedData(conn);
            conn.commit();
        }

        generation = new GenerationCounter();
        service = new WorklogService(ds, generation, dbPath);
    }

    private void createSchema(java.sql.Connection conn) throws SQLException {
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS repos (
                id INTEGER PRIMARY KEY, path TEXT UNIQUE NOT NULL,
                workspace TEXT, family_root TEXT, github_repo TEXT, project_type TEXT)""");
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS slots (
                id INTEGER PRIMARY KEY, slot_number INTEGER NOT NULL,
                family_root TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'active',
                created_at TEXT NOT NULL, archived_at TEXT,
                UNIQUE(slot_number, family_root))""");
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS work_items (
                id INTEGER PRIMARY KEY, branch TEXT NOT NULL,
                repo_id INTEGER NOT NULL REFERENCES repos(id),
                state TEXT NOT NULL DEFAULT 'active', location TEXT NOT NULL DEFAULT 'primary',
                slot_id INTEGER REFERENCES slots(id), work_path TEXT,
                created_at TEXT NOT NULL, ended_at TEXT,
                UNIQUE(branch, repo_id))""");
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS work_item_issues (
                work_item_id INTEGER NOT NULL REFERENCES work_items(id),
                issue_number INTEGER NOT NULL, issue_repo TEXT NOT NULL,
                is_primary INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (work_item_id, issue_number, issue_repo))""");
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY, timestamp TEXT NOT NULL,
                event_type TEXT NOT NULL, work_item_id INTEGER REFERENCES work_items(id),
                slot_id INTEGER REFERENCES slots(id), repo_path TEXT, metadata TEXT)""");
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS issue_enrichment (
                issue_number INTEGER NOT NULL, issue_repo TEXT NOT NULL,
                strategic_role TEXT, readiness TEXT, decay TEXT,
                blast_radius TEXT, cohesion TEXT, updated_at TEXT NOT NULL,
                PRIMARY KEY (issue_number, issue_repo))""");
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS trajectory_notes (
                id INTEGER PRIMARY KEY, issue_number INTEGER NOT NULL,
                issue_repo TEXT NOT NULL, note TEXT NOT NULL,
                source_branch TEXT, created_at TEXT NOT NULL)""");
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS github_issue_cache (
                issue_number INTEGER NOT NULL, issue_repo TEXT NOT NULL,
                title TEXT, state TEXT, labels TEXT, body TEXT,
                cached_at TEXT NOT NULL,
                PRIMARY KEY (issue_number, issue_repo))""");
        conn.createStatement().execute("PRAGMA user_version = 2");
    }

    private void seedData(java.sql.Connection conn) throws SQLException {
        conn.createStatement().execute(
            "INSERT INTO repos (id, path, github_repo) VALUES (1, '/repo/a', 'Org/repoA')");
        conn.createStatement().execute(
            "INSERT INTO work_items (id, branch, repo_id, state, location, created_at) VALUES " +
            "(1, 'issue-42-worklog', 1, 'active', 'primary', '2026-08-11T10:00:00Z')," +
            "(2, 'issue-43-done', 1, 'ended', 'primary', '2026-08-10T10:00:00Z')");
        conn.createStatement().execute(
            "INSERT INTO work_item_issues VALUES (1, 42, 'Hortora/trellis', 1)," +
            "(1, 44, 'Hortora/trellis', 0)");
        conn.createStatement().execute(
            "INSERT INTO events (id, timestamp, event_type, work_item_id, repo_path) VALUES " +
            "(1, '2026-08-11T10:00:00Z', 'work-start', 1, '/repo/a')," +
            "(2, '2026-08-11T11:00:00Z', 'work-continue', 1, '/repo/a')," +
            "(3, '2026-08-10T10:00:00Z', 'work-start', 2, '/repo/a')," +
            "(4, '2026-08-10T12:00:00Z', 'work-end', 2, '/repo/a')");
        conn.createStatement().execute(
            "INSERT INTO slots (id, slot_number, family_root, state, created_at) VALUES " +
            "(1, 7, '/family/root', 'active', '2026-08-11T09:00:00Z')");
        conn.createStatement().execute(
            "INSERT INTO github_issue_cache (issue_number, issue_repo, title, state, labels, cached_at) VALUES " +
            "(10, 'Test/repo', 'Open issue', 'OPEN', '[]', '2026-08-09T10:00:00Z')," +
            "(11, 'Test/repo', 'Closed issue', 'CLOSED', '[]', '2026-08-09T10:00:00Z')");
    }

    @Test
    void recentEventsReturnsDescending() {
        var events = service.recentEvents(null, null, 10);
        assertEquals(4, events.size());
        assertEquals("work-end", events.get(0).eventType());
    }

    @Test
    void recentEventsFiltersByType() {
        var events = service.recentEvents(null, "work-start", 10);
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(e -> "work-start".equals(e.eventType())));
    }

    @Test
    void recentEventsFiltersBySince() {
        var events = service.recentEvents("2026-08-11T00:00:00Z", null, 10);
        assertEquals(2, events.size());
    }

    @Test
    void recentEventsRespectsLimit() {
        var events = service.recentEvents(null, null, 2);
        assertEquals(2, events.size());
    }

    @Test
    void activeWorkExcludesEnded() {
        var items = service.activeWork();
        assertEquals(1, items.size());
        assertEquals("issue-42-worklog", items.get(0).branch());
        assertEquals("active", items.get(0).state());
    }

    @Test
    void activeWorkIncludesIssues() {
        var items = service.activeWork();
        assertEquals(1, items.size());
        var issues = items.get(0).issues();
        assertEquals(2, issues.size());
        assertTrue(issues.stream().anyMatch(i -> i.issueNumber() == 42 && i.isPrimary()));
        assertTrue(issues.stream().anyMatch(i -> i.issueNumber() == 44 && !i.isPrimary()));
    }

    @Test
    void slotStatusReturnsAll() {
        var slots = service.slotStatus(null);
        assertEquals(1, slots.size());
        assertEquals(7, slots.get(0).slotNumber());
        assertEquals("active", slots.get(0).state());
    }

    @Test
    void slotStatusFiltersByFamilyRoot() {
        var slots = service.slotStatus("/nonexistent");
        assertEquals(0, slots.size());
    }

    @Test
    void workItemTimelineReturnsEventsForBranch() {
        var events = service.workItemTimeline("issue-42-worklog", "/repo/a");
        assertEquals(2, events.size());
        assertEquals("work-start", events.get(0).eventType());
        assertEquals("work-continue", events.get(1).eventType());
    }

    @Test
    void workItemTimelineReturnsEmptyForUnknown() {
        var events = service.workItemTimeline("no-such-branch", "/repo/a");
        assertEquals(0, events.size());
    }

    @Test
    void backlogEntriesReturnsOpenIssues() {
        var entries = service.backlogEntries("Test/repo");
        assertEquals(1, entries.size());
        assertEquals("Open issue", entries.get(0).title());
    }

    @Test
    void planPositionParsesActiveLine() throws IOException {
        var planDir = tmpDir.resolve("design");
        Files.createDirectories(planDir);
        Files.writeString(planDir.resolve(".plan"), """
            # Work Plan
            ## Queue
            - [x] #40 — Done task
            - [ ] #42 — Active task ← active
            - [ ] #43 — Future task
            ## Session State
            Current: #42
            """);
        var state = service.planPosition(tmpDir);
        assertNotNull(state);
        assertEquals("#42", state.activeIssue());
        assertEquals(1, state.completed());
        assertEquals(3, state.total());
    }

    @Test
    void planPositionReturnsNullWhenNoPlan() {
        var state = service.planPosition(tmpDir.resolve("nonexistent"));
        assertNull(state);
    }

    @Test
    void schemaVersionTooOldDisablesService() throws SQLException {
        var oldDbPath = tmpDir.resolve("old-worklog.db");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + oldDbPath);
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("PRAGMA user_version = 1");
            conn.createStatement().execute("CREATE TABLE dummy (id INTEGER)");
        }
        var oldService = WorklogService.withSchemaCheck(ds, generation, oldDbPath);
        assertFalse(oldService.isDbAvailable());
        assertEquals(List.of(), oldService.recentEvents(null, null, 10));
    }

    @Test
    void allMethodsReturnEmptyWhenUnavailable() {
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDir.resolve("no-db.db"));
        var svc = new WorklogService(ds, new GenerationCounter(), tmpDir.resolve("no-db.db"));
        svc.dbAvailable = false;

        assertEquals(List.of(), svc.recentEvents(null, null, 10));
        assertEquals(List.of(), svc.activeWork());
        assertEquals(List.of(), svc.slotStatus(null));
        assertEquals(List.of(), svc.backlogEntries(null));
        assertEquals(List.of(), svc.workItemTimeline("x", "/x"));
        assertNull(svc.planPosition(tmpDir));
    }

    @Test
    void summaryReturnsCachedWithinTtl() {
        var s1 = service.summary(tmpDir);
        var s2 = service.summary(tmpDir);
        assertSame(s1, s2);
    }

    @Test
    void summaryContainsExpectedFields() {
        var s = service.summary(tmpDir);
        assertEquals(1, s.activeWorkItems());
        assertEquals(4, s.recentEventCount());
        assertNotNull(s.latestEvent());
        assertEquals(1, s.slotsActive());
    }
}
