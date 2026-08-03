package io.hortora.trellis.agent;

import io.hortora.trellis.terminal.TerminalInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemoryPressureMonitorTest {

    MemoryPressureMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new MemoryPressureMonitor();
    }

    @Test
    void agentExceeding1GBBecomesEvictionCandidate() {
        var snapshot = snapshot("t1", 1_200_000_000L);
        var candidates = monitor.evaluate(List.of(snapshot));
        assertEquals(1, candidates.size());
        assertEquals(EvictionReason.PER_AGENT_CRITICAL, candidates.get(0).reason());
        assertEquals("t1", candidates.get(0).terminalName());
    }

    @Test
    void agentBelow1GBNotCandidate() {
        var snapshot = snapshot("t1", 500_000_000L);
        var candidates = monitor.evaluate(List.of(snapshot));
        assertTrue(candidates.isEmpty());
    }

    @Test
    void firstExceededPreservedAcrossEvaluations() {
        var snapshot = snapshot("t1", 1_200_000_000L);
        var first = monitor.evaluate(List.of(snapshot));
        var firstTime = first.get(0).firstExceeded();

        var second = monitor.evaluate(List.of(snapshot));
        assertEquals(firstTime, second.get(0).firstExceeded());
    }

    @Test
    void agentDroppingBelowThresholdRemovedFromCandidates() {
        var high = snapshot("t1", 1_200_000_000L);
        var candidates = monitor.evaluate(List.of(high));
        assertEquals(1, candidates.size());

        var low = snapshot("t1", 500_000_000L);
        candidates = monitor.evaluate(List.of(low));
        assertTrue(candidates.isEmpty());
    }

    @Test
    void pausedAgentsNotCandidates() {
        var terminal = terminal("t1");
        var paused = new AgentSnapshot("t1", terminal,
                AgentProcess.paused("claude"), null);
        var candidates = monitor.evaluate(List.of(paused));
        assertTrue(candidates.isEmpty());
    }

    @Test
    void idleAgentsNotCandidates() {
        var terminal = terminal("t1");
        var idle = new AgentSnapshot("t1", terminal, null, null);
        var candidates = monitor.evaluate(List.of(idle));
        assertTrue(candidates.isEmpty());
    }

    @Test
    void multipleCandidatesTrackedIndependently() {
        var s1 = snapshot("t1", 1_200_000_000L);
        var s2 = snapshot("t2", 1_500_000_000L);
        var s3 = snapshot("t3", 500_000_000L);
        var candidates = monitor.evaluate(List.of(s1, s2, s3));
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().anyMatch(c -> c.terminalName().equals("t1")));
        assertTrue(candidates.stream().anyMatch(c -> c.terminalName().equals("t2")));
    }

    private static AgentSnapshot snapshot(String name, long memoryBytes) {
        var terminal = terminal(name);
        var process = new AgentProcess(100, AgentState.RUNNING, memoryBytes, Instant.now(), "claude");
        return new AgentSnapshot(name, terminal, process, null);
    }

    private static TerminalInfo terminal(String name) {
        return new TerminalInfo(name, "/tmp", "slot-1", null, null);
    }
}
