package io.hortora.trellis.dependencies;

import java.util.List;
import java.util.Map;

public record DependencyGraph(List<DependencyNode> nodes,
                              List<DependencyEdge> edges,
                              List<IssueRef> criticalPath,
                              Map<IssueStatus, List<DependencyNode>> grouped,
                              Map<IssueRef, String> issueStates) {}
