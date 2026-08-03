package io.hortora.trellis.coordinator;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public sealed interface CoordinatorEvent {
    Instant timestamp();
    String key();

    record WorkspaceChangedEvent(Instant timestamp, String key, Path workspaceRoot) implements CoordinatorEvent {}
    record AnalysisEvent(Instant timestamp, String key, String epicRef, List<String> newlyUnblocked) implements CoordinatorEvent {}
    record IssueEvent(Instant timestamp, String key, String issueKey, String action) implements CoordinatorEvent {}

    record ActionStateChangedEvent(
            Instant timestamp, String key,
            String actionId, ActionStatus oldStatus, ActionStatus newStatus,
            String actionType
    ) implements CoordinatorEvent {}

    record LifecycleOperationEvent(
            Instant timestamp, String key,
            String operation, boolean success, String detail
    ) implements CoordinatorEvent {}

}
