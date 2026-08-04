package io.hortora.trellis.protocol;

public record AddEntryRequest(
        String indexPath,
        String section,
        String file,
        String summary,
        String appliesTo,
        String gardenEntryId,
        String content
) {}
