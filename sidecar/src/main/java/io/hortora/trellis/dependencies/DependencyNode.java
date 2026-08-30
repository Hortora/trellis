package io.hortora.trellis.dependencies;

import java.util.List;

public record DependencyNode(IssueRef ref, String title, String issueState,
                             IssueStatus status, List<IssueRef> blockedBy,
                             List<IssueRef> blocking) {}
