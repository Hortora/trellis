package io.hortora.trellis.issues;

public record EpicKpis(
        int total, int open, int closed,
        int criticalPathLength, int estimatedSerialSteps,
        int bottleneckCount, int maxParallelism
) {}
