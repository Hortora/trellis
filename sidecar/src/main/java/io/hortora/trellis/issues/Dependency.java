package io.hortora.trellis.issues;

public record Dependency(
        String fromKey,
        String toKey,
        boolean resolved
) {}
