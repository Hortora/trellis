package io.hortora.trellis.lifecycle;

import io.casehub.pages.push.EventBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LifecycleOperationTrackerTest {

    private LifecycleOperationTracker tracker;
    private EventBroadcaster broadcaster;
    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        broadcaster = mock(EventBroadcaster.class);
        tracker = new LifecycleOperationTracker(broadcaster, tempDir);
    }

    @Test
    void startOperationCreatesAllStepsPending() {
        var opId = tracker.startOperation("end", "slot-3",
                List.of("stop-agents", "rebase", "push", "stamp"));
        var progress = tracker.getProgress(opId);

        assertNotNull(progress);
        assertEquals("end", progress.operationType());
        assertEquals("slot-3", progress.contextId());
        assertEquals(OperationState.RUNNING, progress.state());
        assertEquals(4, progress.steps().size());
        assertTrue(progress.steps().stream()
                .allMatch(s -> s.state() == StepState.PENDING));
        assertEquals("stop-agents", progress.steps().get(0).name());
    }

    @Test
    void stepStartedTransitionsToRunning() {
        var opId = tracker.startOperation("end", "slot-3", List.of("rebase", "push"));
        tracker.stepStarted(opId, "rebase");
        var progress = tracker.getProgress(opId);

        assertEquals(StepState.RUNNING, progress.steps().get(0).state());
        assertEquals(StepState.PENDING, progress.steps().get(1).state());
        assertNotNull(progress.steps().get(0).startedAt());
        verify(broadcaster, atLeastOnce()).broadcast(eq("lifecycle:progress"), any(Object.class));
    }

    @Test
    void stepCompletedCapturesOutput() {
        var opId = tracker.startOperation("end", "slot-3", List.of("rebase"));
        tracker.stepStarted(opId, "rebase");
        tracker.stepCompleted(opId, "rebase", "SYNCED=yes", "");
        var step = tracker.getProgress(opId).steps().get(0);

        assertEquals(StepState.DONE, step.state());
        assertEquals("SYNCED=yes", step.stdout());
    }

    @Test
    void stepFailedMarksOperationFailed() {
        var opId = tracker.startOperation("end", "slot-3", List.of("rebase", "push"));
        tracker.stepStarted(opId, "rebase");
        tracker.stepFailed(opId, "rebase", "", "error: conflict");
        var progress = tracker.getProgress(opId);

        assertEquals(StepState.FAILED, progress.steps().get(0).state());
        assertEquals(OperationState.FAILED, progress.state());
        assertEquals("error: conflict", progress.steps().get(0).stderr());
    }

    @Test
    void operationFailedTransitionsRunningStepToFailed() {
        var opId = tracker.startOperation("end", "slot-3", List.of("rebase", "push"));
        tracker.stepStarted(opId, "rebase");
        tracker.operationFailed(opId, "Script timed out");
        var progress = tracker.getProgress(opId);

        assertEquals(StepState.FAILED, progress.steps().get(0).state());
        assertEquals(OperationState.FAILED, progress.state());
        assertEquals("Script timed out", progress.errorMessage());
    }

    @Test
    void getActiveOperationByContextId() {
        var opId = tracker.startOperation("end", "slot-3", List.of("rebase"));
        var active = tracker.getActiveOperation("slot-3");

        assertNotNull(active);
        assertEquals(opId, active.operationId());
    }

    @Test
    void startOperationAutoDismissesPreviousCompleted() {
        var opId1 = tracker.startOperation("end", "slot-3", List.of("rebase"));
        tracker.operationCompleted(opId1);
        var opId2 = tracker.startOperation("pause", "slot-3", List.of("commit-wip"));

        assertNull(tracker.getProgress(opId1));
        assertNotNull(tracker.getProgress(opId2));
    }

    @Test
    void filePersistenceOnStartup() {
        var opId = tracker.startOperation("end", "slot-3", List.of("rebase"));
        tracker.stepStarted(opId, "rebase");

        var tracker2 = new LifecycleOperationTracker(broadcaster, tempDir);
        tracker2.loadOnStartup(null);
        var recovered = tracker2.getProgress(opId);

        assertNotNull(recovered);
        assertEquals(OperationState.FAILED, recovered.state());
        assertEquals(StepState.FAILED, recovered.steps().get(0).state());
    }

    @Test
    void operationCompletedTransitionsState() {
        var opId = tracker.startOperation("end", "slot-3", List.of("rebase"));
        tracker.stepStarted(opId, "rebase");
        tracker.stepCompleted(opId, "rebase", "", "");
        tracker.operationCompleted(opId);
        var progress = tracker.getProgress(opId);

        assertEquals(OperationState.COMPLETED, progress.state());
        assertNotNull(progress.completedAt());
    }

    @Test
    void startOperationWithRepoContextId() {
        var opId = tracker.startOperation("end", "repo-engine",
                                          List.of("stop-agents", "rebase", "push", "stamp"));
        var progress = tracker.getProgress(opId);
        assertEquals("repo-engine", progress.contextId());
        assertEquals(OperationState.RUNNING, progress.state());
    }

    @Test
    void concurrentOperationsOnDifferentContextTypes() {
        var op1 = tracker.startOperation("end", "slot-3", List.of("rebase"));
        var op2 = tracker.startOperation("pause", "repo-engine", List.of("commit-wip"));
        assertNotEquals(op1, op2);
        assertNotNull(tracker.getActiveOperation("slot-3"));
        assertNotNull(tracker.getActiveOperation("repo-engine"));
    }

}
