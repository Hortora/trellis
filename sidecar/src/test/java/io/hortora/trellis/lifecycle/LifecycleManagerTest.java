package io.hortora.trellis.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleManagerTest {

    LifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new LifecycleManager();
    }

    @Test
    void lockPreventsSecondAcquisitionFromDifferentThread() throws Exception {
        assertTrue(manager.tryLock("slot-1"));

        var result = java.util.concurrent.Executors.newSingleThreadExecutor()
                                                   .submit(() -> manager.tryLock("slot-1")).get();

        assertFalse(result);
        manager.unlock("slot-1");
    }

    @Test
    void unlockAllowsReacquisition() {
        assertTrue(manager.tryLock("slot-1"));
        manager.unlock("slot-1");
        assertTrue(manager.tryLock("slot-1"));
        manager.unlock("slot-1");
    }

    @Test
    void differentKeysDoNotConflict() {
        assertTrue(manager.tryLock("slot-1"));
        assertTrue(manager.tryLock("slot-2"));
        manager.unlock("slot-1");
        manager.unlock("slot-2");
    }

    @Test
    void sameWorkspaceOperationsConflict() throws Exception {
        assertTrue(manager.tryLock("/workspace/a"));

        var result = java.util.concurrent.Executors.newSingleThreadExecutor()
                .submit(() -> manager.tryLock("/workspace/a")).get();

        assertFalse(result);
        manager.unlock("/workspace/a");
    }

    @Test
    void differentWorkspacesDoNotConflict() throws Exception {
        assertTrue(manager.tryLock("/workspace/a"));

        var exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        var locked = exec.submit(() -> manager.tryLock("/workspace/b")).get();
        assertTrue(locked);
        exec.submit(() -> manager.unlock("/workspace/b")).get();

        manager.unlock("/workspace/a");
    }

    @Test
    void endStepsReportsProgressToTracker(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        var scriptRunner  = org.mockito.Mockito.mock(ScriptRunner.class);
        var successResult = new OperationResult(true, 0, java.util.Map.of(), "", "OK");
        org.mockito.Mockito.when(scriptRunner.run(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                           .thenReturn(successResult);
        manager.scriptRunner = scriptRunner;

        var broadcaster = org.mockito.Mockito.mock(io.casehub.pages.push.EventBroadcaster.class);
        var tracker     = new LifecycleOperationTracker(broadcaster, tempDir);
        var opId = tracker.startOperation("end", "1",
                                          java.util.List.of("rebase", "push", "stamp"));

        manager.endSteps("1", java.nio.file.Path.of("/workspace"), tracker, opId);

        var progress = tracker.getProgress(opId);
        assertTrue(progress.steps().stream()
                           .allMatch(s -> s.state() == StepState.DONE));
    }

    @Test
    void endStepsThrowsStepFailedOnScriptFailure(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        var scriptRunner  = org.mockito.Mockito.mock(ScriptRunner.class);
        var successResult = new OperationResult(true, 0, java.util.Map.of(), "", "");
        var failResult    = new OperationResult(false, 1, java.util.Map.of(), "conflict", "");
        org.mockito.Mockito.when(scriptRunner.run(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                           .thenReturn(successResult)
                           .thenReturn(failResult);
        manager.scriptRunner = scriptRunner;

        var broadcaster = org.mockito.Mockito.mock(io.casehub.pages.push.EventBroadcaster.class);
        var tracker     = new LifecycleOperationTracker(broadcaster, tempDir);
        var opId = tracker.startOperation("end", "1",
                                          java.util.List.of("rebase", "push", "stamp"));

        org.junit.jupiter.api.Assertions.assertThrows(StepFailedException.class,
                                                      () -> manager.endSteps("1", java.nio.file.Path.of("/workspace"), tracker, opId));

        var progress = tracker.getProgress(opId);
        org.junit.jupiter.api.Assertions.assertEquals(StepState.DONE, progress.steps().get(0).state());
        org.junit.jupiter.api.Assertions.assertEquals(StepState.FAILED, progress.steps().get(1).state());
        org.junit.jupiter.api.Assertions.assertEquals(StepState.PENDING, progress.steps().get(2).state());
    }
}
