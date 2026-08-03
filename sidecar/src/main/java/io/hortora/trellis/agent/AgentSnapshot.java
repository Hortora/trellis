package io.hortora.trellis.agent;

import io.hortora.trellis.terminal.TerminalInfo;

public record AgentSnapshot(
        String terminalName,
        TerminalInfo terminal,
        AgentProcess process,
        String lastError
) {}
