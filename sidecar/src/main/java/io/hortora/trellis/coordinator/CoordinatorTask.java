package io.hortora.trellis.coordinator;

public record CoordinatorTask(
        TaskType type,
        int contextTokens,
        int eventCount,
        int conversationDepth,
        String workspaceKey
) {
    public enum TaskType { PROACTIVE_ADVICE, CONVERSATIONAL, DIRECTIVE, RECOMMENDATION_ENHANCEMENT }
}
