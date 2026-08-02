package io.hortora.trellis.garden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GardenSearchResult(
        String id,
        String title,
        String domain,
        String type,
        int score,
        String body,
        double relevance,
        Double crossEncoderScore,
        String source,
        String sourcePrefix) {}
