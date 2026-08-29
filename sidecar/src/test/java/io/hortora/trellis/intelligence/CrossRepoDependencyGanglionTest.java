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

class CrossRepoDependencyGanglionTest {

    private final CrossRepoDependencyGanglion ganglion = new CrossRepoDependencyGanglion();

    private SituationContext initialContext(String correlationKey) {
        return SituationContext.initial("cross-repo-dependency", correlationKey,
                TrellisCloudEvents.TENANCY_ID, Instant.now());
    }

    @Test
    void detectsUnconsumedUpstreamChange() {
        var data = Map.<String, Object>of(
                "upstreamRepo", "casehub-pages",
                "prNumber", 303,
                "prTitle", "feat: add activateDockPanel to LiveSite",
                "downstreamRepo", "trellis",
                "consumed", false,
                "relatedIssues", List.of(49)
        );
        CloudEvent event = TrellisCloudEvents.crossRepoChange(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("casehub-pages#303"));

        assertEquals(DetectionSignal.DETECTED, result.signal());
        assertTrue(result.confidence() >= 0.5);
        assertEquals("casehub-pages", result.evidence().get("upstreamRepo"));
        assertEquals(303, result.evidence().get("prNumber"));
    }

    @Test
    void higherConfidenceWhenRelatedIssuesExist() {
        var data = Map.<String, Object>of(
                "upstreamRepo", "casehub-pages",
                "prNumber", 303,
                "prTitle", "feat: add activateDockPanel",
                "downstreamRepo", "trellis",
                "consumed", false,
                "relatedIssues", List.of(49)
        );
        CloudEvent event = TrellisCloudEvents.crossRepoChange(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("casehub-pages#303"));

        assertTrue(result.confidence() >= 0.8,
                "Related issues should boost confidence to ACTION_NEEDED");
    }

    @Test
    void returnsNoiseWhenAlreadyConsumed() {
        var data = Map.<String, Object>of(
                "upstreamRepo", "casehub-pages",
                "prNumber", 303,
                "prTitle", "feat: add activateDockPanel",
                "downstreamRepo", "trellis",
                "consumed", true,
                "relatedIssues", List.of()
        );
        CloudEvent event = TrellisCloudEvents.crossRepoChange(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("casehub-pages#303"));

        assertEquals(DetectionSignal.NOISE, result.signal());
    }

    @Test
    void lowerConfidenceWithNoRelatedIssues() {
        var data = Map.<String, Object>of(
                "upstreamRepo", "casehub-ras",
                "prNumber", 100,
                "prTitle", "refactor: cleanup",
                "downstreamRepo", "trellis",
                "consumed", false,
                "relatedIssues", List.of()
        );
        CloudEvent event = TrellisCloudEvents.crossRepoChange(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("casehub-ras#100"));

        assertEquals(DetectionSignal.DETECTED, result.signal());
        assertTrue(result.confidence() <= 0.7,
                "No related issues should give ATTENTION confidence");
    }
}
