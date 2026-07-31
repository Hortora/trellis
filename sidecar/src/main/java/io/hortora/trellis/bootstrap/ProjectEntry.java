package io.hortora.trellis.bootstrap;

import java.util.List;

public record ProjectEntry(
    String id,
    String name,
    String description,
    String parentRepoUrl,
    String setupCommand,
    List<String> expectedStructure
) {}
