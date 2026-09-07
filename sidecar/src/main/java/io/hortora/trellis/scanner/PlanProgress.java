package io.hortora.trellis.scanner;

import java.util.List;

public record PlanProgress(List<PlanBatch> batches, String activeIssue, int completed, int total) {}
