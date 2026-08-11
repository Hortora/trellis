package io.hortora.trellis.worklog;

public record WorklogEvent(long id, String timestamp, String eventType,
                            Long workItemId, Long slotId, String repoPath,
                            String metadata) {}
