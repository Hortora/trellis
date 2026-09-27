package io.hortora.trellis.lifecycle;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.Instant;
import java.util.List;

public record OperationProgress(
    String operationId,
    String operationType,
    @JsonAlias("slotId") String contextId,
    OperationState state,
    List<StepProgress> steps,
    Instant startedAt,
    Instant completedAt,
    String errorMessage
) {
    public OperationProgress withStep(String stepName, StepProgress updated) {
        var newSteps = steps.stream()
                .map(s -> s.name().equals(stepName) ? updated : s)
                .toList();
        return new OperationProgress(operationId, operationType, contextId,
                state, List.copyOf(newSteps), startedAt, completedAt, errorMessage);
    }

    public OperationProgress withState(OperationState state) {
        return new OperationProgress(operationId, operationType, contextId,
                state, steps, startedAt,
                state != OperationState.RUNNING ? Instant.now() : completedAt,
                errorMessage);
    }

    public OperationProgress withFailed(String errorMessage) {
        var fixedSteps = steps.stream()
                .map(s -> s.state() == StepState.RUNNING
                        ? s.withOutput(StepState.FAILED, null, errorMessage)
                        : s)
                .toList();
        return new OperationProgress(operationId, operationType, contextId,
                OperationState.FAILED, List.copyOf(fixedSteps), startedAt,
                Instant.now(), errorMessage);
    }
}
