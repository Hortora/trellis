package io.hortora.trellis.dependencies;

import io.hortora.trellis.mcp.GenerationCounter;
import io.hortora.trellis.scanner.FileWatcherService;
import io.hortora.trellis.scanner.RepoInfo;
import io.hortora.trellis.scanner.WorkspaceModel;
import io.hortora.trellis.worklog.WorklogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DependencyServiceTest {

    @TempDir Path tmpDir;
    private DependencyService service;
    private WorklogService worklogService;
    private FileWatcherService fileWatcherService;
    private GenerationCounter generation;
    private SQLiteDataSource ds;

    @BeforeEach
    void setUp() throws SQLException {
        var dbPath = tmpDir.resolve("test.db");
        ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath);
        try (var conn = ds.getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE github_issue_cache (
                    issue_number INTEGER NOT NULL, issue_repo TEXT NOT NULL,
                    title TEXT, state TEXT, labels TEXT, body TEXT,
                    cached_at TEXT NOT NULL, PRIMARY KEY (issue_number, issue_repo))""");
            conn.createStatement().execute("PRAGMA user_version = 2");
        }
        generation = new GenerationCounter();
        worklogService = new WorklogService(ds, generation, dbPath);

        fileWatcherService = mock(FileWatcherService.class);
        var model = new WorkspaceModel(tmpDir, Instant.now(),
            List.of(new RepoInfo("trellis", tmpDir.resolve("trellis"), "main",
                "git@github.com:Hortora/trellis.git")),
            List.of(), List.of(), List.of());
        when(fileWatcherService.currentModel(tmpDir)).thenReturn(model);

        service = new DependencyService(worklogService, fileWatcherService, generation);
    }

    private void insertIssue(int number, String repo, String state, String body) throws SQLException {
        try (var conn = ds.getConnection()) {
            var stmt = conn.prepareStatement(
                "INSERT OR REPLACE INTO github_issue_cache VALUES (?,?,?,?,?,?,?)");
            stmt.setInt(1, number);
            stmt.setString(2, repo);
            stmt.setString(3, "Issue " + number);
            stmt.setString(4, state);
            stmt.setString(5, "[]");
            stmt.setString(6, body);
            stmt.setString(7, "2026-08-30T10:00:00Z");
            stmt.executeUpdate();
        }
    }

    @Test
    void emptyGraphWhenNoIssues() {
        var graph = service.buildGraph(tmpDir);
        assertNotNull(graph);
        assertTrue(graph.nodes().isEmpty());
        assertTrue(graph.edges().isEmpty());
        assertTrue(graph.criticalPath().isEmpty());
    }

    @Test
    void classifiesUnblockedIssue() throws SQLException {
        insertIssue(55, "Hortora/trellis", "OPEN", "blocked by #42");
        insertIssue(42, "Hortora/trellis", "CLOSED", null);
        var graph = service.buildGraph(tmpDir);
        var node55 = graph.nodes().stream()
            .filter(n -> n.ref().number() == 55).findFirst().orElseThrow();
        assertEquals(IssueStatus.UNBLOCKED, node55.status());
    }

    @Test
    void classifiesBlockedIssue() throws SQLException {
        insertIssue(55, "Hortora/trellis", "OPEN", "blocked by #42");
        insertIssue(42, "Hortora/trellis", "OPEN", null);
        var graph = service.buildGraph(tmpDir);
        var node55 = graph.nodes().stream()
            .filter(n -> n.ref().number() == 55).findFirst().orElseThrow();
        assertEquals(IssueStatus.BLOCKED, node55.status());
    }

    @Test
    void classifiesClearIssue() throws SQLException {
        insertIssue(53, "Hortora/trellis", "OPEN", "No dependencies here");
        var graph = service.buildGraph(tmpDir);
        var node53 = graph.nodes().stream()
            .filter(n -> n.ref().number() == 53).findFirst().orElseThrow();
        assertEquals(IssueStatus.CLEAR, node53.status());
    }

    @Test
    void computesCriticalPath() throws SQLException {
        insertIssue(11, "Hortora/trellis", "OPEN", null);
        insertIssue(19, "Hortora/trellis", "OPEN", "blocked by #11");
        insertIssue(42, "Hortora/trellis", "OPEN", "blocked by #19");
        insertIssue(55, "Hortora/trellis", "OPEN", "blocked by #42");
        var graph = service.buildGraph(tmpDir);
        assertEquals(4, graph.criticalPath().size());
        assertEquals(11, graph.criticalPath().getFirst().number());
        assertEquals(55, graph.criticalPath().getLast().number());
    }

    @Test
    void criticalPathExcludesClosedBlockers() throws SQLException {
        insertIssue(11, "Hortora/trellis", "CLOSED", null);
        insertIssue(19, "Hortora/trellis", "OPEN", "blocked by #11");
        insertIssue(42, "Hortora/trellis", "OPEN", "blocked by #19");
        var graph = service.buildGraph(tmpDir);
        assertTrue(graph.criticalPath().stream().noneMatch(r -> r.number() == 11),
            "Closed issue #11 should not appear in critical path");
        assertEquals(2, graph.criticalPath().size(),
            "#19 → #42 is still a real blocking chain");
    }

    @Test
    void criticalPathEmptyWhenAllBlockersResolved() throws SQLException {
        insertIssue(11, "Hortora/trellis", "CLOSED", null);
        insertIssue(19, "Hortora/trellis", "OPEN", "blocked by #11");
        var graph = service.buildGraph(tmpDir);
        assertTrue(graph.criticalPath().isEmpty(),
            "No blocked issues means no critical path");
    }

    @Test
    void groupsByStatus() throws SQLException {
        insertIssue(55, "Hortora/trellis", "OPEN", "blocked by #42");
        insertIssue(42, "Hortora/trellis", "OPEN", null);
        insertIssue(19, "Hortora/trellis", "OPEN", "blocked by #11");
        insertIssue(11, "Hortora/trellis", "CLOSED", null);
        insertIssue(53, "Hortora/trellis", "OPEN", null);
        var graph = service.buildGraph(tmpDir);
        assertEquals(1, graph.grouped().get(IssueStatus.BLOCKED).size());
        assertEquals(1, graph.grouped().get(IssueStatus.UNBLOCKED).size());
        assertEquals(2, graph.grouped().get(IssueStatus.CLEAR).size());
    }

    @Test
    void excludesClosedIssuesFromNodes() throws SQLException {
        insertIssue(11, "Hortora/trellis", "CLOSED", null);
        insertIssue(19, "Hortora/trellis", "OPEN", "blocked by #11");
        var graph = service.buildGraph(tmpDir);
        assertTrue(graph.nodes().stream().noneMatch(n -> n.ref().number() == 11),
            "Closed issues should not appear as nodes");
    }

    @Test
    void cachedGraphReturnsSameInstanceWhenUnchanged() throws SQLException {
        insertIssue(53, "Hortora/trellis", "OPEN", null);
        var g1 = service.buildGraph(tmpDir);
        var g2 = service.buildGraph(tmpDir);
        assertSame(g1, g2);
    }

    @Test
    void cacheInvalidatesOnGenerationChange() throws SQLException {
        insertIssue(53, "Hortora/trellis", "OPEN", null);
        var g1 = service.buildGraph(tmpDir);
        generation.increment();
        var g2 = service.buildGraph(tmpDir);
        assertNotSame(g1, g2);
    }

    @Test
    void extractsOwnerRepoFromSshUrl() {
        assertEquals("Hortora/trellis",
            DependencyService.extractOwnerRepo("git@github.com:Hortora/trellis.git"));
    }

    @Test
    void extractsOwnerRepoFromHttpsUrl() {
        assertEquals("Hortora/trellis",
            DependencyService.extractOwnerRepo("https://github.com/Hortora/trellis.git"));
    }

    @Test
    void returnsNullForUnrecognisedUrl() {
        assertNull(DependencyService.extractOwnerRepo("https://other.host/repo"));
    }

    @Test
    void blockedReturnsSortedByDepth() throws SQLException {
        insertIssue(11, "Hortora/trellis", "OPEN", null);
        insertIssue(19, "Hortora/trellis", "OPEN", "blocked by #11");
        insertIssue(42, "Hortora/trellis", "OPEN", "blocked by #19");
        var blocked = service.blocked(tmpDir);
        assertEquals(2, blocked.size());
        assertEquals(42, blocked.getFirst().ref().number());
        assertEquals(19, blocked.get(1).ref().number());
    }

    @Test
    void returnsEmptyGraphWhenNoFileWatcher() {
        when(fileWatcherService.currentModel(tmpDir)).thenReturn(null);
        var graph = service.buildGraph(tmpDir);
        assertTrue(graph.nodes().isEmpty());
    }

    @Test
    void issueStatesMapIncludesClosedIssues() throws SQLException {
        insertIssue(11, "Hortora/trellis", "CLOSED", null);
        insertIssue(19, "Hortora/trellis", "OPEN", "blocked by #11");
        var graph = service.buildGraph(tmpDir);
        assertEquals("CLOSED", graph.issueStates().get(new IssueRef(11, "Hortora/trellis")));
        assertEquals("OPEN", graph.issueStates().get(new IssueRef(19, "Hortora/trellis")));
    }
}
