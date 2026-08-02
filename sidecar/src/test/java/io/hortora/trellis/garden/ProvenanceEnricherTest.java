package io.hortora.trellis.garden;

import io.hortora.trellis.scanner.FileWatcherService;
import io.hortora.trellis.scanner.SlotInfo;
import io.hortora.trellis.scanner.SlotStatus;
import io.hortora.trellis.scanner.WorkspaceModel;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProvenanceEnricherTest {

    @Test
    void enrichMatchesSlotByIssueRef() {
        var watcher = mock(FileWatcherService.class);
        var slot = new SlotInfo(3, Path.of("/tmp/slot-3"), "Hortora/trellis#14",
                SlotStatus.ACTIVE, false, List.of("trellis", "engine"));
        var model = new WorkspaceModel(Path.of("/tmp"), Instant.now(),
                List.of(), List.of(slot), List.of(), List.of());
        when(watcher.allModels()).thenReturn(List.of(model));

        var enricher = new ProvenanceEnricher();
        setField(enricher, "watcherService", watcher);

        var record = new ProvenanceRecord("Hortora/trellis", 14, "spec.md",
                "GE-0031", Instant.now().toString(), "brainstorming");

        List<EnrichedProvenanceRecord> result = enricher.enrich(List.of(record));

        assertEquals(1, result.size());
        assertNotNull(result.getFirst().workspace());
        assertEquals(3, result.getFirst().workspace().slotNumber());
        assertEquals("ACTIVE", result.getFirst().workspace().slotStatus());
        assertEquals(List.of("trellis", "engine"), result.getFirst().workspace().repos());
    }

    @Test
    void enrichReturnsNullWorkspaceWhenNoMatch() {
        var watcher = mock(FileWatcherService.class);
        when(watcher.allModels()).thenReturn(List.of());

        var enricher = new ProvenanceEnricher();
        setField(enricher, "watcherService", watcher);

        var record = new ProvenanceRecord("Hortora/unknown", 999, "",
                "GE-0031", Instant.now().toString(), "work-start");

        List<EnrichedProvenanceRecord> result = enricher.enrich(List.of(record));

        assertEquals(1, result.size());
        assertNull(result.getFirst().workspace());
    }

    @Test
    void enrichSurvivesWatcherException() {
        var watcher = mock(FileWatcherService.class);
        when(watcher.allModels()).thenThrow(new RuntimeException("watcher down"));

        var enricher = new ProvenanceEnricher();
        setField(enricher, "watcherService", watcher);

        var record = new ProvenanceRecord("Hortora/trellis", 14, "",
                "GE-0031", Instant.now().toString(), "brainstorming");

        List<EnrichedProvenanceRecord> result = enricher.enrich(List.of(record));

        assertEquals(1, result.size());
        assertNull(result.getFirst().workspace());
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
