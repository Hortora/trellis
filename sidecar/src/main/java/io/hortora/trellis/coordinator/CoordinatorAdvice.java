package io.hortora.trellis.coordinator;

import java.time.Instant;

public record CoordinatorAdvice(
        String id,
        AdviceType type,
        String epicRef,
        String title,
        String body,
        String actionKey,
        Instant timestamp
) {
    public enum AdviceType { INSIGHT, WARNING, SUGGESTION, STATUS }
}
