package io.hortora.trellis.scanner;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

public class PathDomainClassifier {

    public enum Domain { REPOS, SLOTS, PROTOCOLS, LIFECYCLE, WORKLOG }

    public Set<Domain> classify(Path changedPath, Path watchRoot) {
        Set<Domain> domains = EnumSet.noneOf(Domain.class);
        String relStr = watchRoot.relativize(changedPath).toString();
        String fileName = changedPath.getFileName().toString();

        if (relStr.contains("/.git/HEAD") || relStr.contains("/.git/refs/")) {
            domains.add(Domain.REPOS);
        }

        if (relStr.startsWith("slots/") &&
            (".slot".equals(fileName) || ".phase-a-complete".equals(fileName))) {
            domains.add(Domain.SLOTS);
        }

        if (relStr.endsWith("/docs/protocols/INDEX.md")) {
            domains.add(Domain.PROTOCOLS);
        }

        if (".plan".equals(fileName) || ".pause-stack".equals(fileName) ||
            ".epic".equals(fileName)) {
            domains.add(Domain.LIFECYCLE);
        }

        if ("worklog.db".equals(fileName)) {
            domains.add(Domain.WORKLOG);
        }

        return domains;
    }
}
