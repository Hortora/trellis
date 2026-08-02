package io.hortora.trellis.garden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProvenanceRecord(
        String issueRepo,
        int issueNumber,
        String specName,
        String geId,
        String recordedAt,
        String recordedBy) {}
