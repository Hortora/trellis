package io.hortora.trellis.coordinator;

import io.casehub.blocks.agentic.routing.RoutingContext;
import io.casehub.blocks.agentic.routing.RoutingDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StaticCoordinatorRoutingTest {

    @Test
    void alwaysReturnsSelected() {
        var routing = new StaticCoordinatorRouting();
        var task = new CoordinatorTask(CoordinatorTask.TaskType.CONVERSATIONAL, 1000, 5, 3, "ws1");
        var context = new RoutingContext<>("test", List.of(), task);

        var decision = routing.route(context).await().indefinitely();
        assertInstanceOf(RoutingDecision.Selected.class, decision);
    }

    @Test
    void selectedReasonIsStaticL1() {
        var routing = new StaticCoordinatorRouting();
        var task = new CoordinatorTask(CoordinatorTask.TaskType.PROACTIVE_ADVICE, 500, 3, 0, "ws1");
        var context = new RoutingContext<>("test", List.of(), task);

        var decision = (RoutingDecision.Selected) routing.route(context).await().indefinitely();
        assertEquals("static-l1", decision.reason());
    }

    @Test
    void worksForAllTaskTypes() {
        var routing = new StaticCoordinatorRouting();
        for (var type : CoordinatorTask.TaskType.values()) {
            var task = new CoordinatorTask(type, 100, 1, 0, "ws1");
            var context = new RoutingContext<>("test", List.of(), task);
            var decision = routing.route(context).await().indefinitely();
            assertInstanceOf(RoutingDecision.Selected.class, decision);
        }
    }
}
