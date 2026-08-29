package io.hortora.trellis.intelligence;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class StalledWorkGanglionTest {

    private final StalledWorkGanglion ganglion = new StalledWorkGanglion();

    private SituationContext initialContext(String correlationKey) {
        return SituationContext.initial("stalled-work", correlationKey,
                TrellisCloudEvents.TENANCY_ID, Instant.now());
    }

    @Test
    void detectsStalledBranchOver7Days() {
        var data = Map.<String, Object>of(
                "branch", "issue-42-worklog",
                "issueNumber", 42,
                "lastEventDaysAgo", 12,
                "state", "active"
        );
        CloudEvent event = TrellisCloudEvents.worklogSnapshot(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("issue-42-worklog"));

        assertEquals(DetectionSignal.DETECTED, result.signal());
        assertTrue(result.confidence() >= 0.6, "confidence should be >= 0.6 for 12 days, got " + result.confidence());
        assertEquals("issue-42-worklog", result.evidence().get("branch"));
        assertEquals(42, result.evidence().get("issueNumber"));
    }

    @Test
    void detectsActionNeededOver14Days() {
        var data = Map.<String, Object>of(
                "branch", "issue-10-stale",
                "issueNumber", 10,
                "lastEventDaysAgo", 18,
                "state", "active"
        );
        CloudEvent event = TrellisCloudEvents.worklogSnapshot(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("issue-10-stale"));

        assertEquals(DetectionSignal.DETECTED, result.signal());
        assertTrue(result.confidence() >= 0.8, "confidence should be >= 0.8 for 18 days, got " + result.confidence());
    }

    @Test
    void returnsNoiseForRecentActivity() {
        var data = Map.<String, Object>of(
                "branch", "issue-49-layout",
                "issueNumber", 49,
                "lastEventDaysAgo", 2,
                "state", "active"
        );
        CloudEvent event = TrellisCloudEvents.worklogSnapshot(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("issue-49-layout"));

        assertEquals(DetectionSignal.NOISE, result.signal());
    }

    @Test
    void returnsNoiseForEndedWorkItem() {
        var data = Map.<String, Object>of(
                "branch", "issue-30-done",
                "issueNumber", 30,
                "lastEventDaysAgo", 20,
                "state", "ended"
        );
        CloudEvent event = TrellisCloudEvents.worklogSnapshot(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("issue-30-done"));

        assertEquals(DetectionSignal.NOISE, result.signal());
    }

    @Test
    void returnsNoiseAtExactThreshold() {
        var data = Map.<String, Object>of(
                "branch", "issue-5-edge",
                "issueNumber", 5,
                "lastEventDaysAgo", 6,
                "state", "active"
        );
        CloudEvent event = TrellisCloudEvents.worklogSnapshot(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("issue-5-edge"));

        assertEquals(DetectionSignal.NOISE, result.signal());
    }

    @Test
    void detectsAtExactThreshold() {
        var data = Map.<String, Object>of(
                "branch", "issue-7-exact",
                "issueNumber", 7,
                "lastEventDaysAgo", 7,
                "state", "active"
        );
        CloudEvent event = TrellisCloudEvents.worklogSnapshot(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("issue-7-exact"));

        assertEquals(DetectionSignal.DETECTED, result.signal());
    }
}
