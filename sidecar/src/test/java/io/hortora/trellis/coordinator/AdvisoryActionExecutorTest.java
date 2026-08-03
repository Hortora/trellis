package io.hortora.trellis.coordinator;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdvisoryActionExecutorTest {

    AdvisoryActionExecutor executor = new AdvisoryActionExecutor();

    @Test
    void acknowledgeReturnsSuccess() {
        var action = new ProposedAction("a1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5", "reason", "unblocks 3 issues"), RiskLevel.LOW, "x",
                ActionStatus.APPROVED, "adv1", "/ws", Instant.now(), null, null, null);
        var result = executor.execute(action);
        assertTrue(result.success());
        assertTrue(result.detail().contains("Acknowledged"));
    }

    @Test
    void investigateReturnsSuccess() {
        var action = new ProposedAction("a2", ActionCategory.ADVISORY, "advisory.investigate",
                Map.of("issueKey", "#7", "reason", "check CI"), RiskLevel.LOW, "y",
                ActionStatus.APPROVED, "adv2", "/ws", Instant.now(), null, null, null);
        var result = executor.execute(action);
        assertTrue(result.success());
    }
}
