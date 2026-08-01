package io.hortora.trellis.coordinator;

import io.casehub.blocks.summarisation.EventAccumulator;
import io.casehub.blocks.summarisation.WindowPolicy;
import io.hortora.trellis.issues.EpicAnalysis;
import io.hortora.trellis.issues.EpicKpis;
import io.hortora.trellis.issues.Recommendation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoordinatorEventObserverTest {

    private EventRing ring;
    private EventAccumulator<CoordinatorEvent> accumulator;
    private CoordinatorEventObserver observer;

    @BeforeEach
    void setUp() {
        ring = new EventRing(16);
        accumulator = new EventAccumulator<>(new WindowPolicy(30_000, 20));
        observer = new CoordinatorEventObserver(ring, accumulator);
    }

    @Test
    void workspaceChangedAddsToRingAndAccumulator() {
        observer.onWorkspaceChanged(Path.of("/tmp/ws1"));

        assertEquals(1, ring.size());
        assertEquals(1, accumulator.size());
        var event = ring.snapshot().get(0);
        assertInstanceOf(CoordinatorEvent.WorkspaceChangedEvent.class, event);
        assertEquals("/tmp/ws1", event.key());
    }

    @Test
    void issuesCacheRefreshedAddsToRingAndAccumulator() {
        observer.onIssuesCacheRefreshed("owner/repo");

        assertEquals(1, ring.size());
        assertEquals(1, accumulator.size());
        var event = ring.snapshot().get(0);
        assertInstanceOf(CoordinatorEvent.IssueEvent.class, event);
        assertEquals("owner/repo", event.key());
    }

    @Test
    void analysisRecomputedAddsToRingAndAccumulator() {
        var analysis = new EpicAnalysis(
                List.of(), null,
                new EpicKpis(5, 3, 2, 3, 2, 1, 2),
                List.of(new Recommendation("owner/repo#5", "Title", Recommendation.Type.CRITICAL_PATH, 100, "reason")),
                List.of(), List.of());
        observer.onAnalysisRecomputed(analysis);

        assertEquals(1, ring.size());
        assertEquals(1, accumulator.size());
        var event = ring.snapshot().get(0);
        assertInstanceOf(CoordinatorEvent.AnalysisEvent.class, event);
    }

    @Test
    void multipleEventsAccumulate() {
        observer.onWorkspaceChanged(Path.of("/tmp/ws1"));
        observer.onIssuesCacheRefreshed("owner/repo");
        observer.onWorkspaceChanged(Path.of("/tmp/ws2"));

        assertEquals(3, ring.size());
        assertEquals(3, accumulator.size());
    }
}
