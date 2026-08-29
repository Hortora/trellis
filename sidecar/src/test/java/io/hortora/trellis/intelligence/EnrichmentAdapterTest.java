package io.hortora.trellis.intelligence;

import io.hortora.trellis.worklog.BacklogEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EnrichmentAdapterTest {

    @Test
    void extractsBlockerFromLabel() {
        var entry = new BacklogEntry(19, "Hortora/trellis", "Work Intelligence",
                List.of("blocked by #11"), null, null, null, null, null, null, null, null, null);

        var blockers = EnrichmentAdapter.extractBlockers(entry);

        assertEquals(1, blockers.size());
        assertEquals(11, blockers.getFirst().get("number"));
        assertEquals("OPEN", blockers.getFirst().get("state"));
    }

    @Test
    void extractsMultipleBlockersFromSingleLabel() {
        var entry = new BacklogEntry(19, "Hortora/trellis", "Work Intelligence",
                List.of("blocked by #11 and #22"), null, null, null, null, null, null, null, null, null);

        var blockers = EnrichmentAdapter.extractBlockers(entry);

        assertEquals(2, blockers.size());
        assertEquals(11, blockers.get(0).get("number"));
        assertEquals(22, blockers.get(1).get("number"));
    }

    @Test
    void returnsEmptyForNonBlockedLabels() {
        var entry = new BacklogEntry(19, "Hortora/trellis", "Work Intelligence",
                List.of("enhancement", "priority:high"), null, null, null, null, null, null, null, null, null);

        var blockers = EnrichmentAdapter.extractBlockers(entry);

        assertTrue(blockers.isEmpty());
    }

    @Test
    void handlesNullLabels() {
        var entry = new BacklogEntry(19, "Hortora/trellis", "Work Intelligence",
                null, null, null, null, null, null, null, null, null, null);

        var blockers = EnrichmentAdapter.extractBlockers(entry);

        assertTrue(blockers.isEmpty());
    }
}
