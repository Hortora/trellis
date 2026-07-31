package io.hortora.trellis.issues;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class EpicBodyParser {

    private static final Pattern CHECKLIST = Pattern.compile(
            "^- \\[([ x])] (?:([\\w.-]+/[\\w.-]+)#(\\d+)|#(\\d+))");
    private static final Pattern BATCH_HEADING = Pattern.compile(
            "^### Batch (\\d+)\\s*[—–-]\\s*(.+)$");

    public static List<String> parseChildren(String body, String owner, String repo) {
        if (body == null || body.isBlank()) return List.of();
        var children = new ArrayList<String>();
        for (var line : body.split("\n")) {
            var m = CHECKLIST.matcher(line.trim());
            if (m.find()) {
                if (m.group(2) != null) {
                    children.add(m.group(2) + "#" + m.group(3));
                } else {
                    children.add(owner + "/" + repo + "#" + m.group(4));
                }
            }
        }
        return children;
    }

    public static List<BatchInfo> parseBatches(String body, String owner, String repo,
                                               List<IssueInfo> issues) {
        if (body == null || body.isBlank()) return List.of();
        var issueStateMap = new HashMap<String, String>();
        for (var issue : issues) {
            issueStateMap.put(issue.key(), issue.state());
        }

        var batches = new ArrayList<BatchInfo>();
        int currentBatch = -1;
        String currentLabel = null;
        var currentIssues = new ArrayList<String>();

        for (var line : body.split("\n")) {
            var bm = BATCH_HEADING.matcher(line.trim());
            if (bm.matches()) {
                if (currentBatch >= 0) {
                    batches.add(buildBatch(currentBatch, currentLabel, currentIssues, issueStateMap, batches));
                }
                currentBatch = Integer.parseInt(bm.group(1));
                currentLabel = bm.group(2).trim();
                currentIssues = new ArrayList<>();
                continue;
            }
            var cm = CHECKLIST.matcher(line.trim());
            if (cm.find()) {
                String key = cm.group(2) != null
                        ? cm.group(2) + "#" + cm.group(3)
                        : owner + "/" + repo + "#" + cm.group(4);
                currentIssues.add(key);
            }
        }

        if (currentBatch >= 0) {
            batches.add(buildBatch(currentBatch, currentLabel, currentIssues, issueStateMap, batches));
        }

        if (batches.isEmpty()) {
            var allChildren = parseChildren(body, owner, repo);
            if (!allChildren.isEmpty()) {
                batches.add(buildBatch(1, "All", allChildren, issueStateMap, batches));
            }
        }

        return batches;
    }

    private static BatchInfo buildBatch(int batch, String label, List<String> issueKeys,
                                        Map<String, String> issueStateMap,
                                        List<BatchInfo> priorBatches) {
        boolean allClosed = !issueKeys.isEmpty() && issueKeys.stream()
                .allMatch(k -> "CLOSED".equals(issueStateMap.get(k)));

        String status;
        if (allClosed) {
            status = "completed";
        } else {
            boolean prevCompleted = priorBatches.isEmpty()
                    || "completed".equals(priorBatches.getLast().status());
            status = prevCompleted ? "active" : "pending";
        }

        return new BatchInfo(batch, label, status, List.copyOf(issueKeys));
    }
}
