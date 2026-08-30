package io.hortora.trellis.dependencies;

public record DependencyEdge(IssueRef blocked, IssueRef blocker) {}
