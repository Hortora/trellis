package io.hortora.trellis.terminal;

public record TerminalInfo(
        String name,
        String workingDir,
        String slot,
        String repo,
        String issue
) {}
