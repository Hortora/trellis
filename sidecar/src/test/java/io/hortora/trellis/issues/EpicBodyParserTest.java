package io.hortora.trellis.issues;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EpicBodyParserTest {

    @Test
    void parsesChildrenFromChecklist() {
        var body = """
                ## Scope
                - [x] #3 — Skeleton
                - [ ] #4 — Scanner
                - [ ] Hortora/other#42 — Cross-repo
                """;
        var children = EpicBodyParser.parseChildren(body, "Hortora", "trellis");
        assertEquals(List.of("Hortora/trellis#3", "Hortora/trellis#4", "Hortora/other#42"), children);
    }

    @Test
    void parsesChildrenFromAnySection() {
        var body = """
                Some intro text.
                - [ ] #1 — First
                ## Tasks
                - [x] #2 — Second
                """;
        var children = EpicBodyParser.parseChildren(body, "O", "R");
        assertEquals(List.of("O/R#1", "O/R#2"), children);
    }

    @Test
    void parsesBatchBoundaries() {
        var body = """
                ### Batch 1 — Foundation
                - [x] #3 — Skeleton
                ### Batch 2 — Core
                - [ ] #4 — Scanner
                - [ ] #5 — Terminal
                ### Batch 3 — Intelligence
                - [ ] #8 — Issue Engine
                """;
        var issues = List.of(
                issue(3, "CLOSED"),
                issue(4, "OPEN"),
                issue(5, "OPEN"),
                issue(8, "OPEN")
        );
        var batches = EpicBodyParser.parseBatches(body, "O", "R", issues);
        assertEquals(3, batches.size());

        assertEquals("Foundation", batches.get(0).label());
        assertEquals("completed", batches.get(0).status());

        assertEquals("Core", batches.get(1).label());
        assertEquals("active", batches.get(1).status());

        assertEquals("Intelligence", batches.get(2).label());
        assertEquals("pending", batches.get(2).status());
    }

    @Test
    void noBatchHeadingsCreatesSingleBatch() {
        var body = """
                - [ ] #1 — First
                - [ ] #2 — Second
                """;
        var issues = List.of(issue(1, "OPEN"), issue(2, "OPEN"));
        var batches = EpicBodyParser.parseBatches(body, "O", "R", issues);
        assertEquals(1, batches.size());
        assertEquals("All", batches.getFirst().label());
        assertEquals("active", batches.getFirst().status());
    }

    @Test
    void emptyBodyReturnsEmpty() {
        assertTrue(EpicBodyParser.parseChildren(null, "O", "R").isEmpty());
        assertTrue(EpicBodyParser.parseChildren("", "O", "R").isEmpty());
    }

    private IssueInfo issue(int number, String state) {
        return new IssueInfo("O", "R", number, "Title " + number, state, List.of(), null, null);
    }
}
