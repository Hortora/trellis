package io.hortora.trellis.issues;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class DependencyParser {

    private static final Pattern LABEL_SAME_REPO = Pattern.compile("^blocked-by:#(\\d+)$");
    private static final Pattern LABEL_CROSS_REPO = Pattern.compile("^blocked-by:([\\w.-]+/[\\w.-]+#\\d+)$");
    private static final Pattern BODY_CHECKLIST = Pattern.compile("^- \\[([ x])] (?:([\\w.-]+/[\\w.-]+)#(\\d+)|#(\\d+))");
    private static final Pattern BLOCKED_BY_FIELD = Pattern.compile("\\*\\*Blocked by:\\*\\*\\s*(.+)");
    private static final Pattern ISSUE_REF = Pattern.compile("(?:([\\w.-]+/[\\w.-]+)#(\\d+)|#(\\d+))");

    public List<Dependency> parse(IssueInfo issue) {
        var seen = new LinkedHashMap<String, Dependency>();
        String fromKey = issue.key();

        parseLabels(issue, fromKey, seen);
        parseBody(issue, fromKey, seen);

        return new ArrayList<>(seen.values());
    }

    private void parseLabels(IssueInfo issue, String fromKey, LinkedHashMap<String, Dependency> seen) {
        for (String label : issue.labels()) {
            Matcher m = LABEL_SAME_REPO.matcher(label);
            if (m.matches()) {
                String toKey = issue.owner() + "/" + issue.repo() + "#" + m.group(1);
                seen.putIfAbsent(toKey, new Dependency(fromKey, toKey, false));
                continue;
            }
            m = LABEL_CROSS_REPO.matcher(label);
            if (m.matches()) {
                String toKey = m.group(1);
                seen.putIfAbsent(toKey, new Dependency(fromKey, toKey, false));
            }
        }
    }

    private void parseBody(IssueInfo issue, String fromKey, LinkedHashMap<String, Dependency> seen) {
        if (issue.body() == null) return;
        var lines = issue.body().split("\n");

        boolean inDepsSection = false;
        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("## ")) {
                inDepsSection = "## Dependencies".equals(trimmed);
                continue;
            }

            Matcher fieldMatcher = BLOCKED_BY_FIELD.matcher(trimmed);
            if (fieldMatcher.matches()) {
                parseBlockedByField(fieldMatcher.group(1), issue, fromKey, seen);
                continue;
            }

            if (!inDepsSection) continue;

            Matcher m = BODY_CHECKLIST.matcher(trimmed);
            if (m.find()) {
                boolean resolved = "x".equals(m.group(1));
                String toKey;
                if (m.group(2) != null) {
                    toKey = m.group(2) + "#" + m.group(3);
                } else {
                    toKey = issue.owner() + "/" + issue.repo() + "#" + m.group(4);
                }
                seen.putIfAbsent(toKey, new Dependency(fromKey, toKey, resolved));
            }
        }
    }

    private void parseBlockedByField(String value, IssueInfo issue, String fromKey, LinkedHashMap<String, Dependency> seen) {
        Matcher m = ISSUE_REF.matcher(value);
        while (m.find()) {
            String toKey;
            if (m.group(1) != null) {
                toKey = m.group(1) + "#" + m.group(2);
            } else {
                toKey = issue.owner() + "/" + issue.repo() + "#" + m.group(3);
            }
            seen.putIfAbsent(toKey, new Dependency(fromKey, toKey, false));
        }
    }
}
