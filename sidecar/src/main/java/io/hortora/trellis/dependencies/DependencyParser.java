package io.hortora.trellis.dependencies;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DependencyParser {

    private static final Pattern INLINE_BLOCKED_BY = Pattern.compile(
        "(?i)(?:blocked\\s+by|depends\\s+on)\\s+(.+?)(?:\\.|$|\\n)", Pattern.MULTILINE);
    private static final Pattern ISSUE_REF = Pattern.compile(
        "(?:([\\w.-]+/[\\w.-]+))?#(\\d+)");
    private static final Pattern SECTION_HEADER = Pattern.compile(
        "^##\\s+(?:Blocked by|Dependencies)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern CHECKLIST_WITH_DEP = Pattern.compile(
        "^-\\s+\\[[ x]]\\s+#(\\d+)\\s+.*?\\((?:blocked by|depends on)\\s+(.+?)\\)",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private DependencyParser() {}

    public static List<DependencyEdge> parseEdges(int issueNumber, String issueRepo, String body) {
        if (body == null || body.isBlank()) return List.of();

        var seen = new LinkedHashSet<DependencyEdge>();
        var defaultRef = new IssueRef(issueNumber, issueRepo);

        parseInlineRefs(body, defaultRef, seen);
        parseSections(body, defaultRef, seen);
        parseChecklistAnnotations(body, issueRepo, seen);

        return List.copyOf(seen);
    }

    private static void parseInlineRefs(String body, IssueRef blocked, LinkedHashSet<DependencyEdge> edges) {
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- [")) continue;
            if (trimmed.startsWith("##")) continue;
            Matcher m = INLINE_BLOCKED_BY.matcher(trimmed);
            while (m.find()) {
                String fragment = m.group(1);
                Matcher refs = ISSUE_REF.matcher(fragment);
                while (refs.find()) {
                    String repo = refs.group(1) != null ? refs.group(1) : blocked.repo();
                    int number = Integer.parseInt(refs.group(2));
                    edges.add(new DependencyEdge(blocked, new IssueRef(number, repo)));
                }
            }
        }
    }

    private static void parseSections(String body, IssueRef blocked, LinkedHashSet<DependencyEdge> edges) {
        Matcher header = SECTION_HEADER.matcher(body);
        while (header.find()) {
            int start = header.end();
            int end = body.indexOf("\n##", start);
            if (end == -1) end = body.length();
            String section = body.substring(start, end);
            for (String line : section.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("-")) continue;
                Matcher refs = ISSUE_REF.matcher(trimmed);
                while (refs.find()) {
                    String repo = refs.group(1) != null ? refs.group(1) : blocked.repo();
                    int number = Integer.parseInt(refs.group(2));
                    edges.add(new DependencyEdge(blocked, new IssueRef(number, repo)));
                }
            }
        }
    }

    private static void parseChecklistAnnotations(String body, String defaultRepo, LinkedHashSet<DependencyEdge> edges) {
        Matcher m = CHECKLIST_WITH_DEP.matcher(body);
        while (m.find()) {
            int childNumber = Integer.parseInt(m.group(1));
            var childRef = new IssueRef(childNumber, defaultRepo);
            String fragment = m.group(2);
            Matcher refs = ISSUE_REF.matcher(fragment);
            while (refs.find()) {
                String repo = refs.group(1) != null ? refs.group(1) : defaultRepo;
                int number = Integer.parseInt(refs.group(2));
                edges.add(new DependencyEdge(childRef, new IssueRef(number, repo)));
            }
        }
    }
}
