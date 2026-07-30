package io.hortora.trellis.terminal;

public record SessionInfo(
        String name,
        String workingDir,
        String slot,
        String repo,
        String issue
) {}
