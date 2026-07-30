package io.hortora.trellis.issues;

import java.time.Instant;
import java.util.List;

public record IssueInfo(
        String owner,
        String repo,
        int number,
        String title,
        String state,
        List<String> labels,
        String body,
        Instant closedAt
) {
    public String key() {
        return owner + "/" + repo + "#" + number;
    }
}
