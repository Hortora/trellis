package io.hortora.trellis.protocol;

import java.nio.file.Path;

public record ProtocolEntry(
        String file,
        String summary,
        String appliesTo,
        Path resolvedPath,
        String section
) {}
