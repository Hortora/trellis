package io.hortora.trellis.lifecycle;

import java.time.Instant;
import java.util.List;

public record OperationProgress(
    String operationId,
    String operationType,
    String slotId,
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
        return new OperationProgress(operationId, operationType, slotId,
                state, List.copyOf(newSteps), startedAt, completedAt, errorMessage);
    }

    public OperationProgress withState(OperationState state) {
        return new OperationProgress(operationId, operationType, slotId,
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
        return new OperationProgress(operationId, operationType, slotId,
                OperationState.FAILED, List.copyOf(fixedSteps), startedAt,
                Instant.now(), errorMessage);
    }
}
