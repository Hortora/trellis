package io.hortora.trellis.scanner;

import java.time.Instant;

public record PauseEntry(
        String branch,
        int issue,
        Instant pausedAt
) {}
