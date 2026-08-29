package io.hortora.trellis.intelligence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeferredItemAdapterTest {

    @TempDir Path tempDir;

    @Test
    void parsesDeferredItemsFromPlanFile() throws IOException {
        var plan = tempDir.resolve(".plan");
        Files.writeString(plan, """
                ## Queue
                - [x] Issue #19 — done
                
                ## Deferred
                - Add pagination (S / Low) — blocked by #33
                - Refactor auth (M / High) — too complex right now
                """);

        var items = DeferredItemAdapter.parseDeferredItems(plan, Instant.now());

        assertEquals(2, items.size());
        assertEquals("Add pagination", items.get(0).get("title"));
        assertEquals("blocked by #33", items.get(0).get("reason"));
        assertEquals("Refactor auth", items.get(1).get("title"));
        assertEquals("too complex right now", items.get(1).get("reason"));
    }

    @Test
    void returnsEmptyForPlanWithNoDeferred() throws IOException {
        var plan = tempDir.resolve(".plan");
        Files.writeString(plan, """
                ## Queue
                - [x] Issue #19 — done
                """);

        var items = DeferredItemAdapter.parseDeferredItems(plan, Instant.now());

        assertTrue(items.isEmpty());
    }

    @Test
    void stopsParsingAtNextSection() throws IOException {
        var plan = tempDir.resolve(".plan");
        Files.writeString(plan, """
                ## Deferred
                - Add pagination (S / Low) — blocked
                
                ## Notes
                - Not a deferred item (S / Low) — should not match
                """);

        var items = DeferredItemAdapter.parseDeferredItems(plan, Instant.now());

        assertEquals(1, items.size());
    }

    @Test
    void handlesNonexistentFile() {
        var items = DeferredItemAdapter.parseDeferredItems(
                tempDir.resolve("nonexistent.plan"), Instant.now());

        assertTrue(items.isEmpty());
    }
}
