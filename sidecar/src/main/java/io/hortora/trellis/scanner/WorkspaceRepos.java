package io.hortora.trellis.scanner;

import io.hortora.trellis.util.PathUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class WorkspaceRepos {

    private static final Pattern SSH_URL = Pattern.compile("git@github\\.com:(.+/.+?)(?:\\.git)?$");
    private static final Pattern HTTPS_URL = Pattern.compile("https://github\\.com/(.+/.+?)(?:\\.git)?$");

    private WorkspaceRepos() {}

    public static List<String> resolve(FileWatcherService watcherService, String root) {
        var rootPath = PathUtil.resolveRoot(root);
        var model = watcherService.currentModel(rootPath);
        if (model == null) return List.of();
        return model.repos().stream()
                .map(r -> extractOwnerRepo(r.remoteUrl()))
                .filter(Objects::nonNull)
                .toList();
    }

    static String extractOwnerRepo(String remoteUrl) {
        if (remoteUrl == null) return null;
        Matcher m = SSH_URL.matcher(remoteUrl);
        if (m.find()) return m.group(1);
        m = HTTPS_URL.matcher(remoteUrl);
        if (m.find()) return m.group(1);
        return null;
    }

    public static Set<String> matchIssueRepos(
            List<String> workspaceOwnerRepos,
            java.util.Collection<String> issueRepos) {
        var wsSet = new HashSet<>(workspaceOwnerRepos);
        var exact = issueRepos.stream().filter(wsSet::contains).collect(Collectors.toSet());
        if (!exact.isEmpty()) return exact;

        var wsNames = workspaceOwnerRepos.stream()
                .map(r -> { int s = r.indexOf('/'); return s >= 0 ? r.substring(s + 1) : r; })
                .collect(Collectors.toSet());
        return issueRepos.stream()
                .filter(r -> { int s = r.indexOf('/'); return s >= 0 && wsNames.contains(r.substring(s + 1)); })
                .collect(Collectors.toSet());
    }


}
