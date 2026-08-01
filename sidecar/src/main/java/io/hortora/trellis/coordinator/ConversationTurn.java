package io.hortora.trellis.coordinator;

import java.time.Instant;

public record ConversationTurn(
        long id,
        String workspaceRoot,
        Role role,
        String content,
        Instant timestamp
) {
    public enum Role { USER, COORDINATOR, SYSTEM }
}
