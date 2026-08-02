package io.hortora.trellis.garden;

public record EnrichedProvenanceRecord(
        String issueRepo,
        int issueNumber,
        String specName,
        String geId,
        String recordedAt,
        String recordedBy,
        WorkspaceContext workspace) {

    public static EnrichedProvenanceRecord from(ProvenanceRecord r, WorkspaceContext workspace) {
        return new EnrichedProvenanceRecord(
                r.issueRepo(), r.issueNumber(), r.specName(),
                r.geId(), r.recordedAt(), r.recordedBy(), workspace);
    }
}
