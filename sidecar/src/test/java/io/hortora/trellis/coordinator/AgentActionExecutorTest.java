package io.hortora.trellis.coordinator;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentActionExecutorTest {

    AgentActionExecutor executor = new AgentActionExecutor();

    @Test
    void allTypesReturnNotAvailable() {
        for (var type : executor.supportedTypes()) {
            var action = new ProposedAction("a1", ActionCategory.AGENT, type,
                    Map.of("terminalName", "t1"), RiskLevel.LOW, "x",
                    ActionStatus.APPROVED, "adv1", "/ws", Instant.now(), null, null, null);
            var result = executor.execute(action);
            assertFalse(result.success());
            assertTrue(result.detail().contains("not yet implemented"));
        }
    }
}
