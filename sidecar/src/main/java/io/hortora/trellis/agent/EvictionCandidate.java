package io.hortora.trellis.agent;

import java.time.Instant;

public record EvictionCandidate(
        String terminalName,
        long memoryBytes,
        Instant firstExceeded,
        EvictionReason reason
) {}
