package io.hortora.trellis.intelligence;

import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ForgottenDeferralGanglionTest {

    private final ForgottenDeferralGanglion ganglion = new ForgottenDeferralGanglion();

    private SituationContext initialContext(String correlationKey) {
        return SituationContext.initial("forgotten-deferral", correlationKey,
                TrellisCloudEvents.TENANCY_ID, Instant.now());
    }

    @Test
    void detectsDeferralWithResolvedBlocker() {
        var data = Map.<String, Object>of(
                "title", "Add pagination to backlog",
                "reason", "blocked by #33",
                "blockerState", "CLOSED",
                "deferredDaysAgo", 21
        );
        CloudEvent event = TrellisCloudEvents.deferredItem(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("Add pagination to backlog"));

        assertEquals(DetectionSignal.DETECTED, result.signal());
        assertTrue(result.confidence() >= 0.6);
    }

    @Test
    void detectsStaleDeferralWithNoBlockerMentioned() {
        var data = Map.<String, Object>of(
                "title", "Refactor auth middleware",
                "reason", "too complex right now",
                "blockerState", "",
                "deferredDaysAgo", 30
        );
        CloudEvent event = TrellisCloudEvents.deferredItem(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("Refactor auth middleware"));

        assertEquals(DetectionSignal.DETECTED, result.signal());
        assertTrue(result.confidence() >= 0.4, "Stale deferral should have ATTENTION confidence");
    }

    @Test
    void returnsNoiseForRecentDeferralWithoutResolvedBlocker() {
        var data = Map.<String, Object>of(
                "title", "Add caching layer",
                "reason", "blocked by #55",
                "blockerState", "OPEN",
                "deferredDaysAgo", 3
        );
        CloudEvent event = TrellisCloudEvents.deferredItem(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("Add caching layer"));

        assertEquals(DetectionSignal.NOISE, result.signal());
    }

    @Test
    void returnsNoiseForRecentDeferralWithNoBlocker() {
        var data = Map.<String, Object>of(
                "title", "Minor cleanup",
                "reason", "low priority",
                "blockerState", "",
                "deferredDaysAgo", 5
        );
        CloudEvent event = TrellisCloudEvents.deferredItem(data);

        DetectionResult result = ganglion.evaluate(event, initialContext("Minor cleanup"));

        assertEquals(DetectionSignal.NOISE, result.signal());
    }
}
