package io.hortora.trellis.issues;

public record DagNode(
        String key,
        int layer,
        int index,
        boolean closed,
        boolean onCriticalPath,
        boolean inCycle,
        boolean external
) {}
