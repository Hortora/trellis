package io.hortora.trellis.lifecycle;

import io.hortora.trellis.agent.AgentProcessManager;
import io.hortora.trellis.agent.AgentState;
import io.hortora.trellis.scanner.WorkspaceChanged;
import io.hortora.trellis.terminal.TerminalRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class SlotAgentCoordinator {

    private static final Logger LOG = Logger.getLogger(SlotAgentCoordinator.class);

    @Inject LifecycleManager lifecycleManager;
    @Inject AgentProcessManager agentProcessManager;
    @Inject TerminalRegistry terminalRegistry;
    @Inject
            LifecycleOperationTracker tracker;
    @Inject
            ManagedExecutor executor;
    @Inject
    @WorkspaceChanged
            Event<Path> workspaceChanged;
    @Inject
            Event<io.hortora.trellis.coordinator.CoordinatorEvent.LifecycleOperationEvent> lifecycleOperationEvent;

    private final ConcurrentHashMap<String, Semaphore> asyncSlotLocks = new ConcurrentHashMap<>();


    private final ConcurrentHashMap<String, ReentrantLock> slotLocks = new ConcurrentHashMap<>();

    public OperationResult coordinatedPause(String slotId, Path workspaceRoot)
            throws IOException, InterruptedException, ConcurrentOperationException {
        var lock = slotLocks.computeIfAbsent(slotId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ConcurrentOperationException("Coordinated operation in progress for slot: " + slotId);
        }
        try {
            shutdownSlotAgents(slotId);
            return lifecycleManager.pause(slotId, workspaceRoot);
        } finally {
            lock.unlock();
        }
    }

    public OperationResult coordinatedResume(String slotId, Path workspaceRoot)
            throws IOException, InterruptedException, ConcurrentOperationException {
        var lock = slotLocks.computeIfAbsent(slotId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ConcurrentOperationException("Coordinated operation in progress for slot: " + slotId);
        }
        try {
            var result = lifecycleManager.resume(slotId, workspaceRoot);
            if (!result.success()) return result;
            resumeCoordinatorPausedAgents(slotId);
            return result;
        } finally {
            lock.unlock();
        }
    }

    public OperationResult coordinatedEnd(String slotId, Path workspaceRoot)
            throws IOException, InterruptedException, ConcurrentOperationException {
        var lock = slotLocks.computeIfAbsent(slotId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ConcurrentOperationException("Coordinated operation in progress for slot: " + slotId);
        }
        try {
            stopAllSlotAgents(slotId);
            return lifecycleManager.end(slotId, workspaceRoot);
        } finally {
            lock.unlock();
        }
    }


    public OperationProgress coordinatedEndAsync(String slotId, Path workspaceRoot)
        throws ConcurrentOperationException {
        var semaphore = asyncSlotLocks.computeIfAbsent(slotId, k -> new Semaphore(1));
        if (!semaphore.tryAcquire()) {
            throw new ConcurrentOperationException("Coordinated operation in progress for slot: " + slotId);
        }
        var operationId = tracker.startOperation("end", slotId,
                                                 List.of("stop-agents", "rebase", "push", "stamp"));
        var progress = tracker.getProgress(operationId);

        executor.submit(() -> {
            try {
                tracker.stepStarted(operationId, "stop-agents");
                stopAllSlotAgents(slotId);
                tracker.stepCompleted(operationId, "stop-agents", "", "");

                lifecycleManager.endSteps(slotId, workspaceRoot, tracker, operationId);
                tracker.operationCompleted(operationId);
                fireLifecycleEvent("end", true, null);
                fireWorkspaceChanged(workspaceRoot);
            } catch (StepFailedException e) {
                fireLifecycleEvent("end", false, e.getMessage());
            } catch (Exception e) {
                tracker.operationFailed(operationId, e.getMessage());
                fireLifecycleEvent("end", false, e.getMessage());
            } finally {
                semaphore.release();
            }
        });
        return progress;
    }

    public OperationProgress coordinatedPauseAsync(String slotId, Path workspaceRoot)
        throws ConcurrentOperationException {
        var semaphore = asyncSlotLocks.computeIfAbsent(slotId, k -> new Semaphore(1));
        if (!semaphore.tryAcquire()) {
            throw new ConcurrentOperationException("Coordinated operation in progress for slot: " + slotId);
        }
        var operationId = tracker.startOperation("pause", slotId,
                                                 List.of("shutdown-agents", "commit-wip", "push-and-stack"));
        var progress = tracker.getProgress(operationId);

        executor.submit(() -> {
            try {
                tracker.stepStarted(operationId, "shutdown-agents");
                shutdownSlotAgents(slotId);
                tracker.stepCompleted(operationId, "shutdown-agents", "", "");

                lifecycleManager.pauseSteps(slotId, workspaceRoot, tracker, operationId);
                tracker.operationCompleted(operationId);
                fireLifecycleEvent("pause", true, null);
                fireWorkspaceChanged(workspaceRoot);
            } catch (StepFailedException e) {
                fireLifecycleEvent("pause", false, e.getMessage());
            } catch (Exception e) {
                tracker.operationFailed(operationId, e.getMessage());
                fireLifecycleEvent("pause", false, e.getMessage());
            } finally {
                semaphore.release();
            }
        });
        return progress;
    }

    public OperationProgress coordinatedResumeAsync(String slotId, Path workspaceRoot)
        throws ConcurrentOperationException {
        var semaphore = asyncSlotLocks.computeIfAbsent(slotId, k -> new Semaphore(1));
        if (!semaphore.tryAcquire()) {
            throw new ConcurrentOperationException("Coordinated operation in progress for slot: " + slotId);
        }
        var operationId = tracker.startOperation("resume", slotId,
                                                 List.of("checkout-branches", "rebase", "reset-wip", "resume-agents"));
        var progress = tracker.getProgress(operationId);

        executor.submit(() -> {
            try {
                lifecycleManager.resumeSteps(slotId, workspaceRoot, tracker, operationId);

                tracker.stepStarted(operationId, "resume-agents");
                resumeCoordinatorPausedAgents(slotId);
                tracker.stepCompleted(operationId, "resume-agents", "", "");

                tracker.operationCompleted(operationId);
                fireLifecycleEvent("resume", true, null);
                fireWorkspaceChanged(workspaceRoot);
            } catch (StepFailedException e) {
                fireLifecycleEvent("resume", false, e.getMessage());
            } catch (Exception e) {
                tracker.operationFailed(operationId, e.getMessage());
                fireLifecycleEvent("resume", false, e.getMessage());
            } finally {
                semaphore.release();
            }
        });
        return progress;
    }

    private void fireLifecycleEvent(String operation, boolean success, String detail) {
        try {
            if (lifecycleOperationEvent != null) {
                var event = new io.hortora.trellis.coordinator.CoordinatorEvent.LifecycleOperationEvent(
                        java.time.Instant.now(), operation, operation, success, detail);
                lifecycleOperationEvent.fireAsync(event);
            }
        } catch (Exception e) {
            LOG.debugf(e, "Failed to fire LifecycleOperationEvent for %s", operation);
        }
    }

    private void fireWorkspaceChanged(Path root) {
        try {
            workspaceChanged.fire(root);
        } catch (Exception e) {
            LOG.debugf(e, "Failed to fire WorkspaceChanged event for %s", root);
        }
    }

    private void shutdownSlotAgents(String slotId) {
        var terminals = terminalRegistry.list().stream()
                .filter(t -> slotId.equals(t.slot()))
                .toList();
        terminals.parallelStream().forEach(t -> {
            var snapshot = agentProcessManager.getSnapshot(t.name(), t);
            if (snapshot.process() != null && snapshot.process().state() == AgentState.RUNNING) {
                try {
                    agentProcessManager.gracefulShutdown(t.name());
                } catch (Exception e) {
                    LOG.warnf("Failed to gracefully shutdown agent %s: %s", t.name(), e.getMessage());
                }
            }
        });
    }

    private void resumeCoordinatorPausedAgents(String slotId) {
        var terminals = terminalRegistry.list().stream()
                .filter(t -> slotId.equals(t.slot()))
                .toList();
        for (var t : terminals) {
            var snapshot = agentProcessManager.getSnapshot(t.name(), t);
            if (snapshot.process() != null
                    && snapshot.process().state() == AgentState.PAUSED_BY_COORDINATOR) {
                try {
                    agentProcessManager.resumeAgent(t.name());
                } catch (Exception e) {
                    LOG.warnf("Failed to resume agent %s: %s", t.name(), e.getMessage());
                }
            }
        }
    }

    private void stopAllSlotAgents(String slotId) {
        var terminals = terminalRegistry.list().stream()
                .filter(t -> slotId.equals(t.slot()))
                .toList();
        terminals.parallelStream().forEach(t -> {
            var snapshot = agentProcessManager.getSnapshot(t.name(), t);
            if (snapshot.process() != null) {
                try {
                    agentProcessManager.stopAgent(t.name());
                } catch (Exception e) {
                    LOG.warnf("Failed to stop agent %s: %s", t.name(), e.getMessage());
                }
            }
        });
    }
}
