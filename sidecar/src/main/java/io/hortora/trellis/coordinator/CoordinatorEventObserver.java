package io.hortora.trellis.coordinator;

import io.casehub.blocks.summarisation.EventAccumulator;
import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.LevelEvent;
import io.hortora.trellis.issues.EpicAnalysis;
import io.hortora.trellis.issues.Recommendation;
import io.hortora.trellis.scanner.WorkspaceChanged;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.time.Instant;

@ApplicationScoped
public class CoordinatorEventObserver {

    private static final EventLevel COORDINATOR_LEVEL = new EventLevel("coordinator", 0);

    private final EventRing ring;
    private final EventAccumulator<CoordinatorEvent> accumulator;

    @Inject
    public CoordinatorEventObserver(EventRing ring, EventAccumulator<CoordinatorEvent> accumulator) {
        this.ring = ring;
        this.accumulator = accumulator;
    }

    public void onWorkspaceChanged(@Observes @WorkspaceChanged Path workspaceRoot) {
        var event = new CoordinatorEvent.WorkspaceChangedEvent(
                Instant.now(), workspaceRoot.toString(), workspaceRoot);
        dispatch(event);
    }

    public void onAnalysisRecomputed(@ObservesAsync @AnalysisRecomputed EpicAnalysis analysis) {
        var unblocked = analysis.recommendations().stream()
                .map(Recommendation::key).toList();
        var event = new CoordinatorEvent.AnalysisEvent(
                Instant.now(), "analysis", "", unblocked);
        dispatch(event);
    }

    public void onIssuesCacheRefreshed(@ObservesAsync @IssuesCacheRefreshed String ownerRepo) {
        var event = new CoordinatorEvent.IssueEvent(
                Instant.now(), ownerRepo, ownerRepo, "cache-refreshed");
        dispatch(event);
    }

    public void onLifecycleOperation(
            @ObservesAsync CoordinatorEvent.LifecycleOperationEvent event) {
        dispatch(event);
    }


    private void dispatch(CoordinatorEvent event) {
        ring.add(event);
        accumulator.collect(new LevelEvent<>(event, event.timestamp().toEpochMilli(), COORDINATOR_LEVEL));
    }
}
