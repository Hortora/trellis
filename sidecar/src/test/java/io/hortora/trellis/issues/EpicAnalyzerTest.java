package io.hortora.trellis.issues;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EpicAnalyzerTest {

    private final RecommendationEngine recEngine = new RecommendationEngine();
    private final DependencyParser parser = new DependencyParser();
    private final EpicAnalyzer analyzer = new EpicAnalyzer(parser, recEngine);

    @Test
    void analyzesSimpleEpic() {
        var epicBody = """
                - [x] #1 — Done
                - [ ] #2 — In progress
                - [ ] #3 — Blocked
                """;
        var issues = List.of(
                issue("O", "R", 0, "Epic", "OPEN", epicBody),
                issue("O", "R", 1, "Done", "CLOSED", null),
                issue("O", "R", 2, "In progress", "OPEN", "**Blocked by:** #1"),
                issue("O", "R", 3, "Blocked", "OPEN", "**Blocked by:** #2")
        );

        var result = analyzer.analyze("O", "R", 0, issues);

        assertEquals(3, result.kpis().total());
        assertEquals(2, result.kpis().open());
        assertEquals(1, result.kpis().closed());
        assertFalse(result.recommendations().isEmpty());
        assertTrue(result.cycleWarning().isEmpty());
    }

    @Test
    void externalBlockersIncludedInGraphButNotKpis() {
        var epicBody = "- [ ] #1 — Child";
        var issues = List.of(
                issue("O", "R", 0, "Epic", "OPEN", epicBody),
                issue("O", "R", 1, "Child", "OPEN", "**Blocked by:** E/e#99"),
                issue("E", "e", 99, "External", "OPEN", null)
        );

        var result = analyzer.analyze("O", "R", 0, issues);

        assertEquals(1, result.kpis().total());
        var extNodes = result.graph().nodes().stream().filter(DagNode::external).toList();
        assertFalse(extNodes.isEmpty());
    }

    @Test
    void externalBlockersExcludedFromRecommendations() {
        var epicBody = "- [ ] #1 — Child";
        var issues = List.of(
                issue("O", "R", 0, "Epic", "OPEN", epicBody),
                issue("O", "R", 1, "Child", "OPEN", "**Blocked by:** E/e#99"),
                issue("E", "e", 99, "External", "OPEN", null)
        );

        var result = analyzer.analyze("O", "R", 0, issues);

        var recKeys = result.recommendations().stream().map(Recommendation::key).toList();
        assertFalse(recKeys.contains("E/e#99"));
    }

    @Test
    void edgeDirectionIsBlockerFirst() {
        var epicBody = "- [ ] #1 — A\n- [ ] #2 — B";
        var issues = List.of(
                issue("O", "R", 0, "Epic", "OPEN", epicBody),
                issue("O", "R", 1, "A", "OPEN", null),
                issue("O", "R", 2, "B", "OPEN", "**Blocked by:** #1")
        );

        var result = analyzer.analyze("O", "R", 0, issues);

        var edge = result.graph().edges().stream()
                .filter(e -> e.target().equals("O/R#2"))
                .findFirst().orElseThrow();
        assertEquals("O/R#1", edge.source());
    }

    @Test
    void batchesExtractedFromEpicBody() {
        var epicBody = """
                ### Batch 1 — Foundation
                - [x] #1 — Done
                ### Batch 2 — Core
                - [ ] #2 — Open
                """;
        var issues = List.of(
                issue("O", "R", 0, "Epic", "OPEN", epicBody),
                issue("O", "R", 1, "Done", "CLOSED", null),
                issue("O", "R", 2, "Open", "OPEN", null)
        );

        var result = analyzer.analyze("O", "R", 0, issues);

        assertEquals(2, result.batches().size());
        assertEquals("completed", result.batches().get(0).status());
        assertEquals("active", result.batches().get(1).status());
    }

    private IssueInfo issue(String owner, String repo, int number, String title,
                            String state, String body) {
        return new IssueInfo(owner, repo, number, title, state, List.of(), body, null);
    }
}
