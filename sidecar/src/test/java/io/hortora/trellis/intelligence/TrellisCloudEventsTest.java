package io.hortora.trellis.intelligence;

import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TrellisCloudEventsTest {

    @Test
    void worklogSnapshotCreatesCloudEventWithCorrectTypeAndTenancy() {
        var data = Map.<String, Object>of("branch", "issue-42", "lastEventDaysAgo", 12);
        CloudEvent event = TrellisCloudEvents.worklogSnapshot(data);

        assertEquals("trellis.worklog.snapshot", event.getType());
        assertEquals("trellis", event.getExtension("tenancyid"));
        assertEquals("trellis-intelligence", event.getSource().toString());
        assertNotNull(event.getId());
        assertNotNull(event.getTime());
        assertNotNull(event.getData());
    }

    @Test
    void enrichmentIssueCreatesCorrectType() {
        var data = Map.<String, Object>of("issueNumber", 19, "state", "OPEN");
        CloudEvent event = TrellisCloudEvents.enrichmentIssue(data);

        assertEquals("trellis.enrichment.issue", event.getType());
        assertEquals("trellis", event.getExtension("tenancyid"));
    }

    @Test
    void deferredItemCreatesCorrectType() {
        var data = Map.<String, Object>of("title", "Add pagination");
        CloudEvent event = TrellisCloudEvents.deferredItem(data);

        assertEquals("trellis.deferred.item", event.getType());
        assertEquals("trellis", event.getExtension("tenancyid"));
    }

    @Test
    void crossRepoChangeCreatesCorrectType() {
        var data = Map.<String, Object>of("upstreamRepo", "casehub-pages");
        CloudEvent event = TrellisCloudEvents.crossRepoChange(data);

        assertEquals("trellis.crossrepo.change", event.getType());
        assertEquals("trellis", event.getExtension("tenancyid"));
    }

    @Test
    void eachEventGetsUniqueId() {
        var data = Map.<String, Object>of("branch", "test");
        CloudEvent e1 = TrellisCloudEvents.worklogSnapshot(data);
        CloudEvent e2 = TrellisCloudEvents.worklogSnapshot(data);

        assertNotEquals(e1.getId(), e2.getId());
    }
}
