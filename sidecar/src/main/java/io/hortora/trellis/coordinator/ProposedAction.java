package io.hortora.trellis.coordinator;

import java.time.Instant;
import java.util.Map;

public record ProposedAction(
        String id,
        ActionCategory category,
        String actionType,
        Map<String, String> params,
        RiskLevel risk,
        String rationale,
        ActionStatus status,
        String adviceId,
        String workspace,
        Instant proposedAt,
        Instant resolvedAt,
        String executionResult,
        Instant countdownEndsAt
) {}
