package io.hortora.trellis.issues;

public record EpicSummary(
        String issueKey, String title,
        int criticalPathLength, int bottleneckCount,
        Recommendation topRecommendation,
        Progress progress
) {}
