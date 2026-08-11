package io.hortora.trellis.worklog;

public record WorklogSummary(int activeWorkItems, int recentEventCount,
                              WorklogEvent latestEvent, PlanState planPosition,
                              int slotsActive) {}
