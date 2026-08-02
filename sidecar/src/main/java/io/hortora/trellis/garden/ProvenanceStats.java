package io.hortora.trellis.garden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProvenanceStats(
        int totalRecords,
        int uniqueEntries,
        int uniqueIssues,
        List<EntryRefCount> topReferenced,
        int unreferencedCount) {}
