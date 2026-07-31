package io.hortora.trellis.issues;

public record Recommendation(
        String key,
        String title,
        Type type,
        int score,
        String reason
) {
    public enum Type { CRITICAL_PATH, BOTTLENECK }
}
