package io.hortora.trellis.scanner;

import java.nio.file.Path;
import java.util.List;

public record RepoInfo(
        String name,
        Path path,
        String branch,
        String remoteUrl,
        String issue,
        List<Integer> covers,
        String workState,
        PlanProgress planProgress
) {
    public RepoInfo(String name, Path path, String branch, String remoteUrl) {
        this(name, path, branch, remoteUrl, null, List.of(), null, null);
    }
}
