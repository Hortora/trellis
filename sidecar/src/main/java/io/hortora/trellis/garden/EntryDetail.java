package io.hortora.trellis.garden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EntryDetail(
        String id,
        String title,
        String domain,
        String type,
        int score,
        String body,
        String source,
        String sourcePrefix,
        List<String> seeAlsoIds) {}
