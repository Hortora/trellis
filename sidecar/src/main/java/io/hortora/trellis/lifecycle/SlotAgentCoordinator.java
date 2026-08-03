package io.hortora.trellis.lifecycle;

import io.hortora.trellis.agent.AgentProcessManager;
import io.hortora.trellis.agent.AgentState;
import io.hortora.trellis.terminal.TerminalRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class SlotAgentCoordinator {

    private static final Logger LOG = Logger.getLogger(SlotAgentCoordinator.class);

    @Inject LifecycleManager lifecycleManager;
    @Inject AgentProcessManager agentProcessManager;
    @Inject TerminalRegistry terminalRegistry;

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
