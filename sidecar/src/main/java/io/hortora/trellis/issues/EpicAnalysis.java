package io.hortora.trellis.issues;

import java.util.List;

public record EpicAnalysis(
        List<IssueInfo> issues,
        GraphData graph,
        EpicKpis kpis,
        List<Recommendation> recommendations,
        List<BatchInfo> batches,
        List<String> cycleWarning
) {}
