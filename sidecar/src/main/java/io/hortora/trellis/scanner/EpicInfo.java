package io.hortora.trellis.scanner;

import java.nio.file.Path;

public record EpicInfo(
        String issue,
        int currentBatch,
        String currentIssue,
        int completedChildren,
        int totalChildren,
        Path path
) {}
