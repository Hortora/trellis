package io.hortora.trellis.issues;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyParserTest {

    final DependencyParser parser = new DependencyParser();

    // --- Label parsing ---

    @Test
    void parsesBlockedByLabelSameRepo() {
        var issue = issue("casehubio", "engine", 42, List.of("blocked-by:#10"), "");

        var deps = parser.parse(issue);

        assertEquals(1, deps.size());
        assertEquals("casehubio/engine#42", deps.getFirst().fromKey());
        assertEquals("casehubio/engine#10", deps.getFirst().toKey());
        assertFalse(deps.getFirst().resolved());
    }

    @Test
    void parsesBlockedByLabelCrossRepo() {
        var issue = issue("casehubio", "engine", 42, List.of("blocked-by:casehubio/platform#5"), "");

        var deps = parser.parse(issue);

        assertEquals(1, deps.size());
        assertEquals("casehubio/platform#5", deps.getFirst().toKey());
    }

    @Test
    void parsesMultipleBlockedByLabels() {
        var issue = issue("org", "repo", 10, List.of("blocked-by:#1", "blocked-by:#2", "bug"), "");

        var deps = parser.parse(issue);

        assertEquals(2, deps.size());
    }

    @Test
    void ignoresNonBlockedByLabels() {
        var issue = issue("org", "repo", 10, List.of("bug", "priority:high"), "");

        var deps = parser.parse(issue);

        assertTrue(deps.isEmpty());
    }

    // --- Body parsing ---

    @Test
    void parsesUncheckedDependencyFromBody() {
        var issue = issue("org", "repo", 10, List.of(), """
                ## Dependencies
                - [ ] #5 — need this first
                """);

        var deps = parser.parse(issue);

        assertEquals(1, deps.size());
        assertEquals("org/repo#5", deps.getFirst().toKey());
        assertFalse(deps.getFirst().resolved());
    }

    @Test
    void parsesCheckedDependencyAsResolved() {
        var issue = issue("org", "repo", 10, List.of(), """
                ## Dependencies
                - [x] #5 — done
                """);

        var deps = parser.parse(issue);

        assertEquals(1, deps.size());
        assertTrue(deps.getFirst().resolved());
    }

    @Test
    void parsesCrossRepoDependencyFromBody() {
        var issue = issue("org", "repo", 10, List.of(), """
                ## Dependencies
                - [ ] other/project#3 — cross-repo dep
                """);

        var deps = parser.parse(issue);

        assertEquals(1, deps.size());
        assertEquals("other/project#3", deps.getFirst().toKey());
    }

    @Test
    void parsesMultipleDependenciesFromBody() {
        var issue = issue("org", "repo", 10, List.of(), """
                ## Dependencies
                - [x] #1 — done
                - [ ] #2 — pending
                - [ ] other/repo#3 — cross-repo
                """);

        var deps = parser.parse(issue);

        assertEquals(3, deps.size());
    }

    @Test
    void ignoresChecklistItemsOutsideDependenciesSection() {
        var issue = issue("org", "repo", 10, List.of(), """
                ## Scope
                - [ ] #5 — this is a scope item, not a dependency
                
                ## Dependencies
                - [ ] #1 — real dep
                
                ## Notes
                - [ ] #9 — not a dependency
                """);

        var deps = parser.parse(issue);

        assertEquals(1, deps.size());
        assertEquals("org/repo#1", deps.getFirst().toKey());
    }

    @Test
    void handlesNullBody() {
        var issue = issue("org", "repo", 10, List.of(), null);

        var deps = parser.parse(issue);

        assertTrue(deps.isEmpty());
    }

    @Test
    void handlesBodyWithNoDependenciesSection() {
        var issue = issue("org", "repo", 10, List.of(), """
                Some description without a dependencies section.
                """);

        var deps = parser.parse(issue);

        assertTrue(deps.isEmpty());
    }

    // --- Combined label + body ---

    @Test
    void combinesLabelAndBodyDependencies() {
        var issue = issue("org", "repo", 10, List.of("blocked-by:#1"), """
                ## Dependencies
                - [ ] #2 — from body
                """);

        var deps = parser.parse(issue);

        assertEquals(2, deps.size());
    }

    @Test
    void deduplicatesLabelAndBodyDependencies() {
        var issue = issue("org", "repo", 10, List.of("blocked-by:#5"), """
                ## Dependencies
                - [ ] #5 — same dep in body
                """);

        var deps = parser.parse(issue);

        assertEquals(1, deps.size());
    }

    // --- Blocked by from issue body field ---

    @Test
    void parsesBlockedByFieldInBody() {
        var issue = issue("org", "repo", 10, List.of(), """
                **Blocked by:** #5
                """);

        var deps = parser.parse(issue);

        assertEquals(1, deps.size());
        assertEquals("org/repo#5", deps.getFirst().toKey());
    }

    @Test
    void parsesMultipleBlockedByInField() {
        var issue = issue("org", "repo", 10, List.of(), """
                **Blocked by:** #5 #8 other/repo#3
                """);

        var deps = parser.parse(issue);

        assertEquals(3, deps.size());
    }

    // --- Helpers ---

    private IssueInfo issue(String owner, String repo, int number, List<String> labels, String body) {
        return new IssueInfo(owner, repo, number, "", "OPEN", labels, body, null);
    }
}
