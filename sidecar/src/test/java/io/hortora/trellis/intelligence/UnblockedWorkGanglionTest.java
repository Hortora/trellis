package io.hortora.trellis.intelligence;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UnblockedWorkGanglionTest {

    private final UnblockedWorkGanglion ganglion = new UnblockedWorkGanglion();

    private SituationContext initialContext(String correlationKey) {
        return SituationContext.initial("unblocked-work", correlationKey,
                TrellisCloudEvents.TENANCY_ID, Instant.now());
    }

    @Test
    void detectsUnblockedIssue() {
        var data = Map.<String, Object>of(
                "issueNumber", 19,
                "state", "OPEN",
                "blockedBy", List.of(Map.of("number", 11, "state", "CLOSED"))
        );
        CloudEvent event = TrellisCloudEvents.enrichmentIssue(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("19"));

        assertEquals(DetectionSignal.DETECTED, result.signal());
        assertTrue(result.confidence() >= 0.8);
        assertEquals(19, result.evidence().get("issueNumber"));
    }

    @Test
    void returnsNoiseWhenBlockerStillOpen() {
        var data = Map.<String, Object>of(
                "issueNumber", 19,
                "state", "OPEN",
                "blockedBy", List.of(Map.of("number", 11, "state", "OPEN"))
        );
        CloudEvent event = TrellisCloudEvents.enrichmentIssue(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("19"));

        assertEquals(DetectionSignal.NOISE, result.signal());
    }

    @Test
    void returnsNoiseWhenNoBlockers() {
        var data = Map.<String, Object>of(
                "issueNumber", 19,
                "state", "OPEN",
                "blockedBy", List.of()
        );
        CloudEvent event = TrellisCloudEvents.enrichmentIssue(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("19"));

        assertEquals(DetectionSignal.NOISE, result.signal());
    }

    @Test
    void returnsNoiseWhenIssueClosed() {
        var data = Map.<String, Object>of(
                "issueNumber", 19,
                "state", "CLOSED",
                "blockedBy", List.of(Map.of("number", 11, "state", "CLOSED"))
        );
        CloudEvent event = TrellisCloudEvents.enrichmentIssue(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("19"));

        assertEquals(DetectionSignal.NOISE, result.signal());
    }

    @Test
    void detectsPartiallyUnblocked() {
        var data = Map.<String, Object>of(
                "issueNumber", 19,
                "state", "OPEN",
                "blockedBy", List.of(
                        Map.of("number", 11, "state", "CLOSED"),
                        Map.of("number", 22, "state", "OPEN")
                )
        );
        CloudEvent event = TrellisCloudEvents.enrichmentIssue(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("19"));

        assertEquals(DetectionSignal.NOISE, result.signal(),
                "Should be noise when not all blockers are resolved");
    }

    @Test
    void detectsAllBlockersResolved() {
        var data = Map.<String, Object>of(
                "issueNumber", 19,
                "state", "OPEN",
                "blockedBy", List.of(
                        Map.of("number", 11, "state", "CLOSED"),
                        Map.of("number", 22, "state", "CLOSED")
                )
        );
        CloudEvent event = TrellisCloudEvents.enrichmentIssue(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("19"));

        assertEquals(DetectionSignal.DETECTED, result.signal());
    }
}
