package io.hortora.trellis.lifecycle;

import io.hortora.trellis.agent.AgentProcess;
import io.hortora.trellis.agent.AgentProcessManager;
import io.hortora.trellis.agent.AgentSnapshot;
import io.hortora.trellis.agent.AgentState;
import io.hortora.trellis.terminal.TerminalInfo;
import io.hortora.trellis.terminal.TerminalRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlotAgentCoordinatorTest {

    static final Path WORKSPACE = Path.of("/ws");

    LifecycleManager lifecycleManager;
    AgentProcessManager agentManager;
    TerminalRegistry registry;
    SlotAgentCoordinator coordinator;

    @BeforeEach
    void setUp() {
        lifecycleManager = mock(LifecycleManager.class);
        agentManager = mock(AgentProcessManager.class);
        registry = mock(TerminalRegistry.class);
        coordinator = new SlotAgentCoordinator();
        // Wire via field injection
        try {
            var lmField = SlotAgentCoordinator.class.getDeclaredField("lifecycleManager");
            lmField.setAccessible(true);
            lmField.set(coordinator, lifecycleManager);
            var amField = SlotAgentCoordinator.class.getDeclaredField("agentProcessManager");
            amField.setAccessible(true);
            amField.set(coordinator, agentManager);
            var trField = SlotAgentCoordinator.class.getDeclaredField("terminalRegistry");
            trField.setAccessible(true);
            trField.set(coordinator, registry);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    void coordinatedPauseShutsDownAgentsBeforeGitOps() throws Exception {
        var t1 = new TerminalInfo("t1", "/tmp", "slot-1", null, null);
        var t2 = new TerminalInfo("t2", "/tmp", "slot-1", null, null);
        var t3 = new TerminalInfo("t3", "/tmp", "slot-2", null, null);
        when(registry.list()).thenReturn(List.of(t1, t2, t3));
        when(agentManager.getSnapshot("t1", t1)).thenReturn(
                new AgentSnapshot("t1", t1, runningAgent(), null));
        when(agentManager.getSnapshot("t2", t2)).thenReturn(
                new AgentSnapshot("t2", t2, runningAgent(), null));
        when(agentManager.getSnapshot("t3", t3)).thenReturn(
                new AgentSnapshot("t3", t3, runningAgent(), null));
        when(lifecycleManager.pause("slot-1", WORKSPACE))
                .thenReturn(new OperationResult(true, 0, Map.of(), ""));

        coordinator.coordinatedPause("slot-1", WORKSPACE);

        verify(agentManager).gracefulShutdown("t1");
        verify(agentManager).gracefulShutdown("t2");
        verify(agentManager, never()).gracefulShutdown("t3");
        verify(lifecycleManager).pause("slot-1", WORKSPACE);
    }

    @Test
    void coordinatedResumeRestartsOnlyCoordinatorPausedAgents() throws Exception {
        var t1 = new TerminalInfo("t1", "/tmp", "slot-1", null, null);
        var t2 = new TerminalInfo("t2", "/tmp", "slot-1", null, null);
        when(registry.list()).thenReturn(List.of(t1, t2));
        when(agentManager.getSnapshot("t1", t1)).thenReturn(
                new AgentSnapshot("t1", t1, AgentProcess.pausedByCoordinator("claude"), null));
        when(agentManager.getSnapshot("t2", t2)).thenReturn(
                new AgentSnapshot("t2", t2, AgentProcess.paused("claude"), null));
        when(lifecycleManager.resume("slot-1", WORKSPACE))
                .thenReturn(new OperationResult(true, 0, Map.of(), ""));

        coordinator.coordinatedResume("slot-1", WORKSPACE);

        verify(agentManager).resumeAgent("t1");
        verify(agentManager, never()).resumeAgent("t2");
    }

    @Test
    void coordinatedResumeSkipsAgentsOnGitFailure() throws Exception {
        when(lifecycleManager.resume("slot-1", WORKSPACE))
                .thenReturn(new OperationResult(false, 1, Map.of(), "rebase failed"));

        var result = coordinator.coordinatedResume("slot-1", WORKSPACE);

        assertFalse(result.success());
        verify(agentManager, never()).resumeAgent(any());
    }

    @Test
    void concurrentOperationsOnSameSlotRejected() throws Exception {
        when(registry.list()).thenReturn(List.of());
        when(lifecycleManager.pause(eq("slot-1"), any()))
                .thenAnswer(inv -> {
                    Thread.sleep(200);
                    return new OperationResult(true, 0, Map.of(), "");
                });

        var future = Executors.newSingleThreadExecutor().submit(() -> {
            coordinator.coordinatedPause("slot-1", WORKSPACE);
            return null;
        });
        Thread.sleep(50);

        assertThrows(ConcurrentOperationException.class,
                () -> coordinator.coordinatedPause("slot-1", WORKSPACE));
        future.get();
    }

    @Test
    void coordinatedEndStopsAllAgentsIncludingPaused() throws Exception {
        var t1 = new TerminalInfo("t1", "/tmp", "slot-1", null, null);
        var t2 = new TerminalInfo("t2", "/tmp", "slot-1", null, null);
        when(registry.list()).thenReturn(List.of(t1, t2));
        when(agentManager.getSnapshot("t1", t1)).thenReturn(
                new AgentSnapshot("t1", t1, runningAgent(), null));
        when(agentManager.getSnapshot("t2", t2)).thenReturn(
                new AgentSnapshot("t2", t2, AgentProcess.paused("claude"), null));
        when(lifecycleManager.end("slot-1", WORKSPACE))
                .thenReturn(new OperationResult(true, 0, Map.of(), ""));

        coordinator.coordinatedEnd("slot-1", WORKSPACE);

        verify(agentManager).stopAgent("t1");
        verify(agentManager).stopAgent("t2");
        verify(lifecycleManager).end("slot-1", WORKSPACE);
    }

    @Test
    void agentShutdownFailureDoesNotBlockPause() throws Exception {
        var t1 = new TerminalInfo("t1", "/tmp", "slot-1", null, null);
        when(registry.list()).thenReturn(List.of(t1));
        when(agentManager.getSnapshot("t1", t1)).thenReturn(
                new AgentSnapshot("t1", t1, runningAgent(), null));
        doThrow(new RuntimeException("agent stuck")).when(agentManager).gracefulShutdown("t1");
        when(lifecycleManager.pause("slot-1", WORKSPACE))
                .thenReturn(new OperationResult(true, 0, Map.of(), ""));

        var result = coordinator.coordinatedPause("slot-1", WORKSPACE);

        assertTrue(result.success());
        verify(lifecycleManager).pause("slot-1", WORKSPACE);
    }

    private static AgentProcess runningAgent() {
        return new AgentProcess(101, AgentState.RUNNING, 204800, Instant.now(), "claude");
    }
}
