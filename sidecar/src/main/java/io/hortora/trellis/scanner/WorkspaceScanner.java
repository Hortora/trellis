package io.hortora.trellis.scanner;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class WorkspaceScanner {

    private static final Logger LOG = Logger.getLogger(WorkspaceScanner.class);
    private static final Pattern ISSUE_PATTERN = Pattern.compile("^([\\w-]+/[\\w-]+#\\d+)");
    private static final Pattern CURRENT_BATCH_PATTERN = Pattern.compile("^Current batch:\\s*(\\d+)");
    private static final Pattern CURRENT_ISSUE_PATTERN = Pattern.compile("^Current issue:\\s*(.+)");
    private static final Pattern CHECKED_CHILD_PATTERN = Pattern.compile("^- \\[x]\\s+#\\d+");
    private static final Pattern UNCHECKED_CHILD_PATTERN = Pattern.compile("^- \\[ ]\\s+#\\d+");
    private static final Pattern REPO_LINE_PATTERN = Pattern.compile("^- (\\S+)");
    private static final Pattern PAUSE_BRANCH_PATTERN = Pattern.compile("^\\s*-?\\s*branch:\\s*(.+)");
    private static final Pattern PAUSE_ISSUE_PATTERN = Pattern.compile("^\\s+issue:\\s*(\\d+)");
    private static final Pattern PAUSE_TIME_PATTERN = Pattern.compile("^\\s+paused:\\s*(.+)");

    public WorkspaceModel scan(Path root) {
        var repos = scanRepos(root);
        var slots = scanSlots(root);
        var pauses = new ArrayList<PauseEntry>();
        var epics = new ArrayList<EpicInfo>();

        scanWorkspaces(root, pauses, epics);

        return new WorkspaceModel(root, Instant.now(), List.copyOf(repos), List.copyOf(slots),
                List.copyOf(pauses), List.copyOf(epics));
    }

    private List<RepoInfo> scanRepos(Path root) {
        var repos = new ArrayList<RepoInfo>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) continue;
                String name = entry.getFileName().toString();
                if ("worktrees".equals(name) || "slots".equals(name) || name.startsWith(".")) continue;

                Path gitDir = entry.resolve(".git");
                if (!Files.isDirectory(gitDir)) continue;
                if (Files.exists(gitDir.resolve("index.lock"))) {
                    LOG.warnf("Skipping repo %s — index.lock present", name);
                    continue;
                }

                String branch = readBranch(gitDir);
                String remoteUrl = readRemoteUrl(gitDir);
                repos.add(new RepoInfo(name, entry, branch, remoteUrl));
            }
        } catch (IOException e) {
            LOG.warnf(e, "Failed to scan repos under %s", root);
        }
        return repos;
    }

    private List<SlotInfo> scanSlots(Path root) {
        var slots = new ArrayList<SlotInfo>();
        Path slotsDir = root.resolve("slots");
        if (!Files.isDirectory(slotsDir)) return slots;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(slotsDir)) {
            for (Path slotDir : stream) {
                if (!Files.isDirectory(slotDir)) continue;
                String dirName = slotDir.getFileName().toString();
                if ("attic".equals(dirName)) continue;

                int number;
                try {
                    number = Integer.parseInt(dirName);
                } catch (NumberFormatException e) {
                    continue;
                }

                Path slotFile = slotDir.resolve(".slot");
                if (!Files.isRegularFile(slotFile)) continue;

                try {
                    SlotInfo info = parseSlotFile(slotFile, slotDir, number);
                    if (info != null) slots.add(info);
                } catch (Exception e) {
                    LOG.warnf(e, "Skipping corrupted slot file: %s", slotFile);
                }
            }
        } catch (IOException e) {
            LOG.warnf(e, "Failed to scan slots under %s", slotsDir);
        }
        return slots;
    }

    private void scanWorkspaces(Path root, List<PauseEntry> pauses, List<EpicInfo> epics) {
        Path slotsDir = root.resolve("slots");
        if (!Files.isDirectory(slotsDir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(slotsDir)) {
            for (Path slotDir : stream) {
                if (!Files.isDirectory(slotDir)) continue;
                String dirName = slotDir.getFileName().toString();
                if ("attic".equals(dirName)) continue;

                try (DirectoryStream<Path> slotContents = Files.newDirectoryStream(slotDir)) {
                    for (Path child : slotContents) {
                        if (!Files.isDirectory(child)) continue;
                        Path designDir = child.resolve("design");
                        if (!Files.isDirectory(designDir)) continue;

                        scanPauseStack(designDir.resolve(".pause-stack"), pauses);
                        scanEpicFile(designDir.resolve(".epic"), epics);
                    }
                }
            }
        } catch (IOException e) {
            LOG.warnf(e, "Failed to scan workspaces under %s", slotsDir);
        }
    }

    private SlotInfo parseSlotFile(Path slotFile, Path slotDir, int number) throws IOException {
        var lines = Files.readAllLines(slotFile);
        String issue = null;
        boolean isEpic = false;
        var repos = new ArrayList<String>();
        boolean inReposSection = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("## ")) {
                inReposSection = "## Repos".equals(trimmed);
                continue;
            }

            if (issue == null && !trimmed.isEmpty() && !trimmed.startsWith("#")) {
                Matcher m = ISSUE_PATTERN.matcher(trimmed);
                if (m.find()) issue = m.group(1);
            }

            if (trimmed.startsWith("Type:") && trimmed.contains("epic")) {
                isEpic = true;
            }

            if (inReposSection) {
                Matcher m = REPO_LINE_PATTERN.matcher(trimmed);
                if (m.find()) repos.add(m.group(1));
            }
        }

        if (issue == null) return null;

        boolean readyToLand = Files.exists(slotDir.resolve(".phase-a-complete"));
        SlotStatus status = readyToLand ? SlotStatus.READY_TO_LAND : SlotStatus.ACTIVE;

        return new SlotInfo(number, slotDir, issue, status, isEpic, List.copyOf(repos));
    }

    private void scanPauseStack(Path pauseFile, List<PauseEntry> pauses) {
        if (!Files.isRegularFile(pauseFile)) return;
        try {
            var lines = Files.readAllLines(pauseFile);
            String branch = null;
            int issue = 0;
            Instant pausedAt = null;

            for (String line : lines) {
                Matcher branchMatcher = PAUSE_BRANCH_PATTERN.matcher(line);
                if (branchMatcher.matches()) {
                    if (branch != null && issue > 0) {
                        pauses.add(new PauseEntry(branch, issue, pausedAt));
                    }
                    branch = branchMatcher.group(1).trim();
                    issue = 0;
                    pausedAt = null;
                    continue;
                }

                Matcher issueMatcher = PAUSE_ISSUE_PATTERN.matcher(line);
                if (issueMatcher.matches()) {
                    issue = Integer.parseInt(issueMatcher.group(1));
                    continue;
                }

                Matcher timeMatcher = PAUSE_TIME_PATTERN.matcher(line);
                if (timeMatcher.matches()) {
                    try {
                        pausedAt = Instant.parse(timeMatcher.group(1).trim());
                    } catch (Exception e) {
                        LOG.debugf("Unparseable pause timestamp: %s", timeMatcher.group(1));
                    }
                }
            }

            if (branch != null && issue > 0) {
                pauses.add(new PauseEntry(branch, issue, pausedAt));
            }
        } catch (IOException e) {
            LOG.warnf(e, "Failed to read pause stack: %s", pauseFile);
        }
    }

    private void scanEpicFile(Path epicFile, List<EpicInfo> epics) {
        if (!Files.isRegularFile(epicFile)) return;
        try {
            var lines = Files.readAllLines(epicFile);
            String issue = null;
            int currentBatch = 0;
            String currentIssue = null;
            int completed = 0;
            int total = 0;

            for (String line : lines) {
                String trimmed = line.trim();

                if (issue == null) {
                    Matcher m = ISSUE_PATTERN.matcher(trimmed);
                    if (m.find()) issue = m.group(1);
                }

                Matcher batchMatcher = CURRENT_BATCH_PATTERN.matcher(trimmed);
                if (batchMatcher.matches()) {
                    currentBatch = Integer.parseInt(batchMatcher.group(1));
                }

                Matcher issueMatcher = CURRENT_ISSUE_PATTERN.matcher(trimmed);
                if (issueMatcher.matches()) {
                    currentIssue = issueMatcher.group(1).trim();
                }

                if (CHECKED_CHILD_PATTERN.matcher(trimmed).find()) {
                    completed++;
                    total++;
                } else if (UNCHECKED_CHILD_PATTERN.matcher(trimmed).find()) {
                    total++;
                }
            }

            if (issue != null) {
                epics.add(new EpicInfo(issue, currentBatch, currentIssue, completed, total, epicFile));
            }
        } catch (IOException e) {
            LOG.warnf(e, "Failed to read epic file: %s", epicFile);
        }
    }

    private String readBranch(Path gitDir) {
        try {
            String head = Files.readString(gitDir.resolve("HEAD")).trim();
            if (head.startsWith("ref: refs/heads/")) {
                return head.substring("ref: refs/heads/".length());
            }
            return head.substring(0, Math.min(head.length(), 8));
        } catch (IOException e) {
            return "unknown";
        }
    }

    private String readRemoteUrl(Path gitDir) {
        Path config = gitDir.resolve("config");
        if (!Files.isRegularFile(config)) return null;
        try {
            var lines = Files.readAllLines(config);
            boolean inOrigin = false;
            for (String line : lines) {
                String trimmed = line.trim();
                if ("[remote \"origin\"]".equals(trimmed)) {
                    inOrigin = true;
                    continue;
                }
                if (trimmed.startsWith("[")) {
                    inOrigin = false;
                    continue;
                }
                if (inOrigin && trimmed.startsWith("url =")) {
                    return trimmed.substring("url =".length()).trim();
                }
            }
        } catch (IOException e) {
            LOG.debugf(e, "Failed to read git config: %s", config);
        }
        return null;
    }
}
