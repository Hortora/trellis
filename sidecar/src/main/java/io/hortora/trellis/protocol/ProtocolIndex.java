package io.hortora.trellis.protocol;

import java.nio.file.Path;

public record ProtocolIndex(
        String repoName,
        Path repoPath,
        Path indexPath,
        String relativePath
) {}
