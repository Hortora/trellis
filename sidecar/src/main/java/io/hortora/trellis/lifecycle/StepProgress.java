package io.hortora.trellis.lifecycle;

import java.time.Instant;

public record StepProgress(
    String name,
    StepState state,
    String summary,
    String stdout,
    String stderr,
    Instant startedAt,
    Instant completedAt
) {
    public StepProgress(String name) {
        this(name, StepState.PENDING, name, null, null, null, null);
    }

    public StepProgress withState(StepState state) {
        return new StepProgress(name, state, summary, stdout, stderr,
                state == StepState.RUNNING ? Instant.now() : startedAt,
                state == StepState.DONE || state == StepState.FAILED ? Instant.now() : completedAt);
    }

    public StepProgress withOutput(StepState state, String stdout, String stderr) {
        return new StepProgress(name, state, summary, stdout, stderr,
                startedAt, Instant.now());
    }
}
