package io.hortora.trellis.coordinator;

import io.casehub.pages.push.EventBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionProposalIntegrationTest {

    @TempDir Path tmpDir;

    @Test
    void fullFlowFromLlmResponseToExecution() {
        var llmResponse = """
                {"type": "SUGGESTION", "title": "Advance epic", "body": "Issue done",
                 "actionKey": "test-action-1",
                 "action": {
                   "category": "LIFECYCLE",
                   "actionType": "epic.next",
                   "params": {"epicPath": "/path/.epic"},
                   "rationale": "Current issue is complete"
                 }}""";

        var parsed = ActionResponseParser.parseAction(llmResponse);
        assertTrue(parsed.isPresent());
        assertEquals("epic.next", parsed.get().actionType());
        assertEquals(ActionCategory.LIFECYCLE, parsed.get().category());
        assertEquals(RiskLevel.LOW, RiskClassification.riskFor("epic.next"));
    }

    @Test
    void actionResponseParserHandlesNoActionField() {
        var llmResponse = """
                {"type": "INSIGHT", "title": "Progress", "body": "Going well"}""";
        assertTrue(ActionResponseParser.parseAction(llmResponse).isEmpty());
    }

    @Test
    void actionServiceStateTransitionsWithRealDb() throws Exception {
        var sds = new SQLiteDataSource();
        sds.setUrl("jdbc:sqlite:" + tmpDir.resolve("integration-" + System.nanoTime() + ".db"));
        new CoordinatorSchemaManager().initialize(sds);

        var executor = new AdvisoryActionExecutor();
        var service = ActionService.forTest(sds, List.of(executor));

        var action = service.propose("adv1", ActionCategory.ADVISORY,
                "advisory.prioritise", Map.of("issueKey", "#5"),
                "unblocks 3", "/ws");
        assertEquals(ActionStatus.PROPOSED, action.status());

        var approved = service.approve(action.id());
        assertEquals(ActionStatus.COMPLETED, approved.status());
        assertNotNull(approved.executionResult());

        var history = service.actionHistory("/ws", 10);
        assertEquals(1, history.size());
        assertEquals(ActionStatus.COMPLETED, history.getFirst().status());
    }

    @Test
    void highRiskFlowRequiresConfirmation() throws Exception {
        var sds = new SQLiteDataSource();
        sds.setUrl("jdbc:sqlite:" + tmpDir.resolve("integration-" + System.nanoTime() + ".db"));
        new CoordinatorSchemaManager().initialize(sds);

        var lifecycleMgr = new LifecycleActionExecutorTest.StubLifecycleManager(
                new io.hortora.trellis.lifecycle.OperationResult(true, 0, Map.of(), ""));
        var lifecycleExec = new LifecycleActionExecutor(lifecycleMgr, null);
        var service = ActionService.forTest(sds, List.of(lifecycleExec));

        var action = service.propose("adv2", ActionCategory.LIFECYCLE,
                "slot.merge", Map.of("slotId", "s1", "workspaceRoot", "/ws"),
                "all tests green", "/ws");
        assertEquals(RiskLevel.HIGH, action.risk());

        var confirming = service.approve(action.id());
        assertEquals(ActionStatus.CONFIRMING, confirming.status());

        var completed = service.confirm(action.id());
        assertEquals(ActionStatus.COMPLETED, completed.status());
    }

    @Test
    void expiryRemovesStaleActions() throws Exception {
        var sds = new SQLiteDataSource();
        sds.setUrl("jdbc:sqlite:" + tmpDir.resolve("integration-" + System.nanoTime() + ".db"));
        new CoordinatorSchemaManager().initialize(sds);

        var lifecycleMgr = new LifecycleActionExecutorTest.StubLifecycleManager(
                new io.hortora.trellis.lifecycle.OperationResult(true, 0, Map.of(), ""));
        var lifecycleExec = new LifecycleActionExecutor(lifecycleMgr, null);
        var service = ActionService.forTest(sds, List.of(lifecycleExec));

        service.propose("adv3", ActionCategory.LIFECYCLE, "slot.merge",
                Map.of("slotId", "s1", "workspaceRoot", "/ws"), "merge", "/ws");
        assertEquals(1, service.pendingActions("/ws").size());

        service.expireStale("slot.merge", Map.of("slotId", "s1"));
        assertEquals(0, service.pendingActions("/ws").size());

        var history = service.actionHistory("/ws", 10);
        assertEquals(1, history.size());
        assertEquals(ActionStatus.EXPIRED, history.getFirst().status());
    }

    @Test
    void circuitBreakerPreventsActionFeedbackLoop() {
        var filter = new SignificanceFilter();
        var events = new java.util.ArrayList<CoordinatorEvent>();
        for (int i = 0; i < 6; i++) {
            events.add(new CoordinatorEvent.ActionStateChangedEvent(
                    java.time.Instant.now(), "ws", "a-" + i,
                    ActionStatus.PROPOSED, ActionStatus.APPROVED, "epic.next"));
        }
        assertFalse(filter.isSignificant(events));
    }
}
