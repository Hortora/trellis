package io.hortora.trellis.worklog;

import java.util.List;

public record WorkItem(long id, String branch, String state, String location,
                       Long slotId, String createdAt, String repoPath,
                       String githubRepo, List<WorkItemIssue> issues) {}
