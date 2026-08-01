package io.hortora.trellis.coordinator;

public record CoordinatorMessageRequest(
        String workspace,
        String epicRef,
        String message
) {}
