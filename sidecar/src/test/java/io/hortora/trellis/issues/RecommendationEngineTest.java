package io.hortora.trellis.issues;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationEngineTest {

    private final RecommendationEngine engine = new RecommendationEngine();

    @Test
    void criticalPathIssueGetsHighestScore() {
        var graph = new DependencyGraph(
                Set.of("R/r#1", "R/r#2", "R/r#3"),
                List.of(dep("R/r#2", "R/r#1"), dep("R/r#3", "R/r#2"))
        );
        var issues = List.of(
                issue("R", "r", 1, "First", "OPEN"),
                issue("R", "r", 2, "Second", "OPEN"),
                issue("R", "r", 3, "Third", "OPEN")
        );
        var recs = engine.recommend(graph, issues, Set.of("R/r#1", "R/r#2", "R/r#3"));

        assertFalse(recs.isEmpty());
        assertEquals("R/r#1", recs.getFirst().key());
        assertEquals(Recommendation.Type.CRITICAL_PATH, recs.getFirst().type());
    }

    @Test
    void onlyUnblockedIssuesGetRecommendations() {
        var graph = new DependencyGraph(
                Set.of("R/r#1", "R/r#2"),
                List.of(dep("R/r#2", "R/r#1"))
        );
        var issues = List.of(
                issue("R", "r", 1, "First", "OPEN"),
                issue("R", "r", 2, "Second", "OPEN")
        );
        var recs = engine.recommend(graph, issues, Set.of("R/r#1", "R/r#2"));

        var keys = recs.stream().map(Recommendation::key).toList();
        assertTrue(keys.contains("R/r#1"));
        assertFalse(keys.contains("R/r#2"));
    }

    @Test
    void externalNodesExcludedFromRecommendations() {
        var graph = new DependencyGraph(
                Set.of("E/e#1", "R/r#1"),
                List.of(dep("R/r#1", "E/e#1"))
        );
        var issues = List.of(
                issue("E", "e", 1, "External", "OPEN"),
                issue("R", "r", 1, "Child", "OPEN")
        );
        var recs = engine.recommend(graph, issues, Set.of("R/r#1"));

        var keys = recs.stream().map(Recommendation::key).toList();
        assertFalse(keys.contains("E/e#1"));
    }

    @Test
    void noRecommendationsWhenAllClosed() {
        var graph = new DependencyGraph(
                Set.of("R/r#1"),
                List.of(),
                Set.of("R/r#1")
        );
        var issues = List.of(issue("R", "r", 1, "Done", "CLOSED"));

        var recs = engine.recommend(graph, issues, Set.of("R/r#1"));

        assertTrue(recs.isEmpty());
    }

    @Test
    void reasonIncludesCascadeCount() {
        var graph = new DependencyGraph(
                Set.of("R/r#1", "R/r#2", "R/r#3"),
                List.of(dep("R/r#2", "R/r#1"), dep("R/r#3", "R/r#1"))
        );
        var issues = List.of(
                issue("R", "r", 1, "Hub", "OPEN"),
                issue("R", "r", 2, "B", "OPEN"),
                issue("R", "r", 3, "C", "OPEN")
        );
        var recs = engine.recommend(graph, issues, Set.of("R/r#1", "R/r#2", "R/r#3"));

        var rec = recs.stream().filter(r -> r.key().equals("R/r#1")).findFirst().orElseThrow();
        assertTrue(rec.reason().contains("critical path"));
        assertTrue(rec.reason().contains("unblock"));
    }

    @Test
    void bottleneckTypeForHighCascadeNotOnCriticalPath() {
        // A -> B -> C (critical path length 3)
        // D -> E, D -> F (D is bottleneck, cascade = 2, but not on critical path)
        var graph = new DependencyGraph(
                Set.of("R/r#1", "R/r#2", "R/r#3", "R/r#4", "R/r#5", "R/r#6"),
                List.of(
                        dep("R/r#2", "R/r#1"), dep("R/r#3", "R/r#2"),
                        dep("R/r#5", "R/r#4"), dep("R/r#6", "R/r#4")
                )
        );
        var issues = List.of(
                issue("R", "r", 1, "A", "OPEN"),
                issue("R", "r", 2, "B", "OPEN"),
                issue("R", "r", 3, "C", "OPEN"),
                issue("R", "r", 4, "D", "OPEN"),
                issue("R", "r", 5, "E", "OPEN"),
                issue("R", "r", 6, "F", "OPEN")
        );
        var childKeys = Set.of("R/r#1", "R/r#2", "R/r#3", "R/r#4", "R/r#5", "R/r#6");
        var recs = engine.recommend(graph, issues, childKeys);

        var dRec = recs.stream().filter(r -> r.key().equals("R/r#4")).findFirst().orElseThrow();
        assertEquals(Recommendation.Type.BOTTLENECK, dRec.type());
        assertTrue(dRec.reason().contains("Bottleneck"));
    }

    private Dependency dep(String from, String to) {
        return new Dependency(from, to, false);
    }

    private IssueInfo issue(String owner, String repo, int number, String title, String state) {
        return new IssueInfo(owner, repo, number, title, state, List.of(), null, null);
    }
}
