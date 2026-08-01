package io.hortora.trellis.coordinator;

import io.hortora.trellis.issues.Recommendation;

import java.time.Instant;
import java.util.List;

public record EnhancedRecommendation(
        Recommendation base,
        String reasoning,
        List<String> contextFactors,
        int adjustedScore,
        Instant generatedAt
) {}
