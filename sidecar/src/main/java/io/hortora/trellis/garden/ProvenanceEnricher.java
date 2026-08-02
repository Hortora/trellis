package io.hortora.trellis.garden;

import io.hortora.trellis.scanner.FileWatcherService;
import io.hortora.trellis.scanner.SlotInfo;
import io.hortora.trellis.scanner.WorkspaceModel;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class ProvenanceEnricher {

    @Inject FileWatcherService watcherService;

    public List<EnrichedProvenanceRecord> enrich(List<ProvenanceRecord> records) {
        return records.stream()
                .map(this::enrichRecord)
                .toList();
    }

    private EnrichedProvenanceRecord enrichRecord(ProvenanceRecord record) {
        try {
            String issueRef = record.issueRepo() + "#" + record.issueNumber();
            WorkspaceContext ctx = findSlotForIssue(issueRef);
            return EnrichedProvenanceRecord.from(record, ctx);
        } catch (Exception e) {
            Log.warnf("Enrichment failed for %s#%d: %s", record.issueRepo(), record.issueNumber(), e.getMessage());
            return EnrichedProvenanceRecord.from(record, null);
        }
    }

    private WorkspaceContext findSlotForIssue(String issueRef) {
        for (WorkspaceModel model : watcherService.allModels()) {
            for (SlotInfo slot : model.slots()) {
                if (issueRef.equals(slot.issue())) {
                    return new WorkspaceContext(
                            slot.number(),
                            slot.status().name(),
                            slot.repos());
                }
            }
        }
        return null;
    }
}
