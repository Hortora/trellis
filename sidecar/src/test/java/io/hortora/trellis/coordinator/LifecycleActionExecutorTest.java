package io.hortora.trellis.coordinator;

import io.hortora.trellis.lifecycle.ConcurrentOperationException;
import io.hortora.trellis.lifecycle.LifecycleManager;
import io.hortora.trellis.lifecycle.OperationResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleActionExecutorTest {

    @Test
    void categoryIsLifecycle() {
        var executor = new LifecycleActionExecutor(new LifecycleManager());
        assertEquals(ActionCategory.LIFECYCLE, executor.category());
    }

    @Test
    void supportsAllLifecycleTypes() {
        var executor = new LifecycleActionExecutor(new LifecycleManager());
        assertTrue(executor.supportedTypes().containsAll(Set.of(
                "lifecycle.start", "lifecycle.end", "lifecycle.pause", "lifecycle.resume",
                "slot.create", "slot.merge", "epic.setup", "epic.next")));
    }

    @Test
    void executesEpicNextSuccessfully() {
        var manager = new StubLifecycleManager(new OperationResult(true, 0, Map.of("status", "advanced"), ""));
        var executor = new LifecycleActionExecutor(manager);
        var action = action("epic.next", Map.of("epicPath", "/path/.epic"));
        var result = executor.execute(action);
        assertTrue(result.success());
        assertEquals(0, result.exitCode());
    }

    @Test
    void executesSlotMerge() {
        var manager = new StubLifecycleManager(new OperationResult(true, 0, Map.of(), ""));
        var executor = new LifecycleActionExecutor(manager);
        var action = action("slot.merge", Map.of("slotId", "s1", "workspaceRoot", "/ws"));
        var result = executor.execute(action);
        assertTrue(result.success());
    }

    @Test
    void listParamsReconstructed() {
        var manager = new StubLifecycleManager(new OperationResult(true, 0, Map.of(), ""));
        var executor = new LifecycleActionExecutor(manager);
        var action = action("slot.create",
                Map.of("workspaceRoot", "/ws", "args.0", "issue-5", "args.1", "my-branch"));
        var result = executor.execute(action);
        assertTrue(result.success());
        assertEquals(List.of("issue-5", "my-branch"), manager.lastArgs);
    }

    @Test
    void ioExceptionConvertedToFailedResult() {
        var manager = new FailingLifecycleManager(new IOException("script error"));
        var executor = new LifecycleActionExecutor(manager);
        var action = action("epic.next", Map.of("epicPath", "/p"));
        var result = executor.execute(action);
        assertFalse(result.success());
        assertTrue(result.detail().contains("script error"));
    }

    @Test
    void concurrentOperationConvertedToFailedResult() {
        var manager = new FailingLifecycleManager(new ConcurrentOperationException("busy"));
        var executor = new LifecycleActionExecutor(manager);
        var action = action("epic.next", Map.of("epicPath", "/p"));
        var result = executor.execute(action);
        assertFalse(result.success());
        assertTrue(result.detail().contains("busy"));
    }

    private ProposedAction action(String actionType, Map<String, String> params) {
        return new ProposedAction("a1", ActionCategory.LIFECYCLE, actionType, params,
                RiskClassification.riskFor(actionType), "test", ActionStatus.APPROVED,
                "adv1", "/ws", Instant.now(), null, null);
    }

    static class StubLifecycleManager extends LifecycleManager {
        final OperationResult result;
        List<String> lastArgs;

        StubLifecycleManager(OperationResult result) { this.result = result; }

        @Override public OperationResult epicNext(String epicPath) { return result; }
        @Override public OperationResult slotMerge(String slotId, Path workspaceRoot) { return result; }
        @Override public OperationResult end(String slotId, Path workspaceRoot) { return result; }
        @Override public OperationResult pause(String slotId, Path workspaceRoot) { return result; }
        @Override public OperationResult resume(String slotId, Path workspaceRoot) { return result; }
        @Override public OperationResult start(Path workspaceRoot, String branch, String issue) { return result; }
        @Override public OperationResult slotCreate(Path workspaceRoot, List<String> args) {
            this.lastArgs = args;
            return result;
        }
        @Override public OperationResult epicSetup(Path workspaceRoot, List<String> args) {
            this.lastArgs = args;
            return result;
        }
    }

    static class FailingLifecycleManager extends LifecycleManager {
        final Exception error;
        FailingLifecycleManager(Exception error) { this.error = error; }

        @Override public OperationResult epicNext(String epicPath)
                throws IOException, InterruptedException, ConcurrentOperationException {
            if (error instanceof IOException e) throw e;
            if (error instanceof ConcurrentOperationException e) throw e;
            throw new RuntimeException(error);
        }
    }
}
