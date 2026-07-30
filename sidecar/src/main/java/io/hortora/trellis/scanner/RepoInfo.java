package io.hortora.trellis.scanner;

import java.nio.file.Path;

public record RepoInfo(
        String name,
        Path path,
        String branch,
        String remoteUrl
) {}
