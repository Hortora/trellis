package io.hortora.trellis.backlog;

import java.util.List;

public record BacklogEntry(
    int issueNumber,
    String issueRepo,
    String title,
    List<String> labels,
    String cachedAt,
    String strategicRole,
    String readiness,
    String decay,
    String blastRadius,
    String cohesion,
    String enrichedAt,
    String trajectoryNote,
    String trajectoryAt
) {}
