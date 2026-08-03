package io.hortora.trellis.agent;

import java.time.Instant;

public record AgentProcess(
        long pid,
        AgentState state,
        long memoryBytes,
        Instant startedAt,
        String command
) {
    public static AgentProcess paused(String command) {
        return new AgentProcess(0, AgentState.PAUSED, 0, null, command);
    }
}
