package io.hortora.trellis.dependencies;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DependencyParserTest {

    @Test
    void parsesInlineBlockedBy() {
        var edges = DependencyParser.parseEdges(55, "Hortora/trellis",
            "This is blocked by #42 and needs work");
        assertEquals(1, edges.size());
        assertEquals(new IssueRef(55, "Hortora/trellis"), edges.getFirst().blocked());
        assertEquals(new IssueRef(42, "Hortora/trellis"), edges.getFirst().blocker());
    }

    @Test
    void parsesInlineDependsOn() {
        var edges = DependencyParser.parseEdges(55, "Hortora/trellis",
            "This depends on #11");
        assertEquals(1, edges.size());
        assertEquals(new IssueRef(11, "Hortora/trellis"), edges.getFirst().blocker());
    }

    @Test
    void parsesCrossRepoBlockedBy() {
        var edges = DependencyParser.parseEdges(55, "Hortora/trellis",
            "blocked by Hortora/soredium#282");
        assertEquals(1, edges.size());
        assertEquals(new IssueRef(282, "Hortora/soredium"), edges.getFirst().blocker());
    }

    @Test
    void parsesBlockedBySection() {
        var body = """
            ## Context
            Some context here.
            
            ## Blocked by
            - #42 — auth migration must land first
            - Hortora/soredium#282 — garden schema change
            
            ## References
            Something else.
            """;
        var edges = DependencyParser.parseEdges(55, "Hortora/trellis", body);
        assertEquals(2, edges.size());
        assertTrue(edges.stream().anyMatch(e -> e.blocker().equals(new IssueRef(42, "Hortora/trellis"))));
        assertTrue(edges.stream().anyMatch(e -> e.blocker().equals(new IssueRef(282, "Hortora/soredium"))));
    }

    @Test
    void parsesDependenciesSection() {
        var body = """
            ## Dependencies
            - #11 — must land first
            """;
        var edges = DependencyParser.parseEdges(55, "Hortora/trellis", body);
        assertEquals(1, edges.size());
        assertEquals(new IssueRef(11, "Hortora/trellis"), edges.getFirst().blocker());
    }

    @Test
    void parsesEpicChecklistAnnotation() {
        var body = """
            ## Children
            - [ ] #55 — user management (blocked by #42)
            - [x] #50 — logging overhaul
            - [ ] #60 — deploy pipeline (depends on #11)
            """;
        var edges = DependencyParser.parseEdges(0, "Hortora/trellis", body);
        assertEquals(2, edges.size());
        assertTrue(edges.stream().anyMatch(e ->
            e.blocked().equals(new IssueRef(55, "Hortora/trellis")) &&
            e.blocker().equals(new IssueRef(42, "Hortora/trellis"))));
        assertTrue(edges.stream().anyMatch(e ->
            e.blocked().equals(new IssueRef(60, "Hortora/trellis")) &&
            e.blocker().equals(new IssueRef(11, "Hortora/trellis"))));
    }

    @Test
    void deduplicatesEdges() {
        var body = """
            blocked by #42
            
            ## Blocked by
            - #42
            """;
        var edges = DependencyParser.parseEdges(55, "Hortora/trellis", body);
        assertEquals(1, edges.size());
    }

    @Test
    void returnsEmptyForNullBody() {
        var edges = DependencyParser.parseEdges(55, "Hortora/trellis", null);
        assertTrue(edges.isEmpty());
    }

    @Test
    void returnsEmptyForBodyWithNoDeps() {
        var edges = DependencyParser.parseEdges(55, "Hortora/trellis",
            "Just a regular issue with no dependencies.");
        assertTrue(edges.isEmpty());
    }

    @Test
    void doesNotParseReferencesAsDependencies() {
        var edges = DependencyParser.parseEdges(55, "Hortora/trellis",
            "See also #42 and #11 for context");
        assertTrue(edges.isEmpty());
    }

    @Test
    void parsesMultipleInlineBlockedBy() {
        var edges = DependencyParser.parseEdges(55, "Hortora/trellis",
            "blocked by #11 and #22");
        assertEquals(2, edges.size());
    }
}
