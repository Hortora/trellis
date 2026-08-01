package io.hortora.trellis.coordinator;

import java.time.Instant;

public record CoordinatorStatus(
        boolean enabled,
        int eventsProcessed,
        Instant lastAdviceTime,
        int conversationDepth,
        int pendingEvents,
        String currentModel
) {}
