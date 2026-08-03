package io.hortora.trellis.artifact;

import java.time.Instant;

public record ArtifactEntry(
        String type,
        String name,
        String path,
        Instant modifiedAt
) {}
