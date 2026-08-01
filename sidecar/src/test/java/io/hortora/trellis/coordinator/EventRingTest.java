package io.hortora.trellis.coordinator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class EventRingTest {

    @Test
    void addsAndSnapshots() {
        var ring = new EventRing(4);
        ring.add(ws("ws1", Instant.now()));
        ring.add(ws("ws1", Instant.now()));
        assertEquals(2, ring.snapshot().size());
    }

    @Test
    void evictsOldestWhenFull() {
        var ring = new EventRing(2);
        var e1 = ws("ws1", Instant.now());
        var e2 = ws("ws1", Instant.now().plusSeconds(1));
        var e3 = ws("ws1", Instant.now().plusSeconds(2));
        ring.add(e1);
        ring.add(e2);
        ring.add(e3);
        var snap = ring.snapshot();
        assertEquals(2, snap.size());
        assertSame(e2, snap.get(0));
        assertSame(e3, snap.get(1));
    }

    @Test
    void snapshotIsDefensiveCopy() {
        var ring = new EventRing(4);
        ring.add(ws("ws1", Instant.now()));
        ring.snapshot().clear();
        assertEquals(1, ring.snapshot().size());
    }

    @Test
    void emptyRingReturnsEmptySnapshot() {
        var ring = new EventRing(4);
        assertTrue(ring.snapshot().isEmpty());
        assertEquals(0, ring.size());
    }

    @Test
    void sizeTracksCorrectly() {
        var ring = new EventRing(3);
        assertEquals(0, ring.size());
        ring.add(ws("ws1", Instant.now()));
        assertEquals(1, ring.size());
        ring.add(ws("ws1", Instant.now()));
        ring.add(ws("ws1", Instant.now()));
        assertEquals(3, ring.size());
        ring.add(ws("ws1", Instant.now())); // evicts
        assertEquals(3, ring.size());
    }

    private CoordinatorEvent.WorkspaceChangedEvent ws(String key, Instant ts) {
        return new CoordinatorEvent.WorkspaceChangedEvent(ts, key, Path.of("/tmp"));
    }
}
