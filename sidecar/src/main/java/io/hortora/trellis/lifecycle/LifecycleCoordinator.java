package io.hortora.trellis.lifecycle;

import io.hortora.trellis.agent.AgentProcessManager;
import io.hortora.trellis.agent.AgentState;
import io.hortora.trellis.scanner.WorkspaceChanged;
import io.hortora.trellis.terminal.TerminalInfo;
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
public class LifecycleCoordinator {

    private static final Logger LOG = Logger.getLogger(LifecycleCoordinator.class);

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

    private final ConcurrentHashMap<String, Semaphore> asyncContextLocks = new ConcurrentHashMap<>();


    private final ConcurrentHashMap<String, ReentrantLock> contextLocks = new ConcurrentHashMap<>();

    public OperationResult coordinatedPause(WorkContext context, Path workspaceRoot)
            throws IOException, InterruptedException, ConcurrentOperationException {
        var lock = contextLocks.computeIfAbsent(context.key(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ConcurrentOperationException("Coordinated operation in progress for: " + context.key());
        }
        try {
            shutdownAgents(context);
            return lifecycleManager.pause(context.key(), workspaceRoot);
        } finally {
            lock.unlock();
        }
    }

    public OperationResult coordinatedResume(WorkContext context, Path workspaceRoot)
            throws IOException, InterruptedException, ConcurrentOperationException {
        var lock = contextLocks.computeIfAbsent(context.key(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ConcurrentOperationException("Coordinated operation in progress for: " + context.key());
        }
        try {
            var result = lifecycleManager.resume(context.key(), workspaceRoot);
            if (!result.success()) return result;
            resumePausedAgents(context);
            return result;
        } finally {
            lock.unlock();
        }
    }

    public OperationResult coordinatedEnd(WorkContext context, Path workspaceRoot)
            throws IOException, InterruptedException, ConcurrentOperationException {
        var lock = contextLocks.computeIfAbsent(context.key(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new ConcurrentOperationException("Coordinated operation in progress for: " + context.key());
        }
        try {
            stopAllAgents(context);
            return lifecycleManager.end(context.key(), workspaceRoot);
        } finally {
            lock.unlock();
        }
    }


    public OperationProgress coordinatedEndAsync(WorkContext context, Path workspaceRoot)
        throws ConcurrentOperationException {
        var semaphore = asyncContextLocks.computeIfAbsent(context.key(), k -> new Semaphore(1));
        if (!semaphore.tryAcquire()) {
            throw new ConcurrentOperationException("Coordinated operation in progress for: " + context.key());
        }
        var operationId = tracker.startOperation("end", context.key(),
                                                 List.of("stop-agents", "rebase", "push", "stamp"));
        var progress = tracker.getProgress(operationId);

        executor.submit(() -> {
            try {
                tracker.stepStarted(operationId, "stop-agents");
                stopAllAgents(context);
                tracker.stepCompleted(operationId, "stop-agents", "", "");

                lifecycleManager.endSteps(context.key(), workspaceRoot, tracker, operationId);
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

    public OperationProgress coordinatedPauseAsync(WorkContext context, Path workspaceRoot)
        throws ConcurrentOperationException {
        var semaphore = asyncContextLocks.computeIfAbsent(context.key(), k -> new Semaphore(1));
        if (!semaphore.tryAcquire()) {
            throw new ConcurrentOperationException("Coordinated operation in progress for: " + context.key());
        }
        var operationId = tracker.startOperation("pause", context.key(),
                                                 List.of("shutdown-agents", "commit-wip", "push-and-stack"));
        var progress = tracker.getProgress(operationId);

        executor.submit(() -> {
            try {
                tracker.stepStarted(operationId, "shutdown-agents");
                shutdownAgents(context);
                tracker.stepCompleted(operationId, "shutdown-agents", "", "");

                lifecycleManager.pauseSteps(context.key(), workspaceRoot, tracker, operationId);
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

    public OperationProgress coordinatedResumeAsync(WorkContext context, Path workspaceRoot)
        throws ConcurrentOperationException {
        var semaphore = asyncContextLocks.computeIfAbsent(context.key(), k -> new Semaphore(1));
        if (!semaphore.tryAcquire()) {
            throw new ConcurrentOperationException("Coordinated operation in progress for: " + context.key());
        }
        var operationId = tracker.startOperation("resume", context.key(),
                                                 List.of("checkout-branches", "rebase", "reset-wip", "resume-agents"));
        var progress = tracker.getProgress(operationId);

        executor.submit(() -> {
            try {
                lifecycleManager.resumeSteps(context.key(), workspaceRoot, tracker, operationId);

                tracker.stepStarted(operationId, "resume-agents");
                resumePausedAgents(context);
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

    private List<TerminalInfo> findTerminals(WorkContext ctx) {
        return switch (ctx) {
            case WorkContext.SlotContext s -> terminalRegistry.list().stream()
                .filter(t -> s.slotId().equals(t.slot())).toList();
            case WorkContext.RepoContext r -> terminalRegistry.list().stream()
                .filter(t -> r.repoName().equals(t.repo()) && t.slot() == null).toList();
        };
    }

    private void shutdownAgents(WorkContext ctx) {
        findTerminals(ctx).parallelStream().forEach(t -> {
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

    private void resumePausedAgents(WorkContext ctx) {
        for (var t : findTerminals(ctx)) {
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

    private void stopAllAgents(WorkContext ctx) {
        findTerminals(ctx).parallelStream().forEach(t -> {
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
