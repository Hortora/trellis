package io.hortora.trellis.coordinator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionServiceTest {

    @TempDir Path tmpDir;

    DataSource dataSource;
    ActionService service;

    @BeforeEach
    void setUp() throws Exception {
        var sds = new SQLiteDataSource();
        sds.setUrl("jdbc:sqlite:" + tmpDir.resolve("test-" + System.nanoTime() + ".db"));
        dataSource = sds;
        new CoordinatorSchemaManager().initialize(dataSource);

        var advisoryExecutor = new AdvisoryActionExecutor();
        var lifecycleExecutor = new LifecycleActionExecutorTest.StubLifecycleManager(
                new io.hortora.trellis.lifecycle.OperationResult(true, 0, Map.of(), ""));
        var lifecycleActionExecutor = new LifecycleActionExecutor(lifecycleExecutor);

        service = ActionService.forTest(dataSource, List.of(lifecycleActionExecutor, advisoryExecutor));
    }

    @Test
    void proposeCreatesActionInProposedState() {
        var action = service.propose("adv1", ActionCategory.LIFECYCLE, "epic.next",
                Map.of("epicPath", "/p"), "ready", "/ws");
        assertNotNull(action.id());
        assertEquals(ActionStatus.PROPOSED, action.status());
        assertEquals("/ws", action.workspace());
        assertEquals(RiskLevel.LOW, action.risk());
    }

    @Test
    void approveLowRiskExecutesImmediately() {
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "ready", "/ws");
        var result = service.approve(action.id());
        assertEquals(ActionStatus.COMPLETED, result.status());
        assertNotNull(result.resolvedAt());
        assertNotNull(result.executionResult());
    }

    @Test
    void approveHighRiskMovesToConfirming() {
        var action = service.propose("adv2", ActionCategory.LIFECYCLE, "slot.merge",
                Map.of("slotId", "s1", "workspaceRoot", "/ws"), "done", "/ws");
        var confirming = service.approve(action.id());
        assertEquals(ActionStatus.CONFIRMING, confirming.status());
        assertNull(confirming.resolvedAt());
    }

    @Test
    void confirmExecutesHighRisk() {
        var action = service.propose("adv3", ActionCategory.LIFECYCLE, "slot.merge",
                Map.of("slotId", "s1", "workspaceRoot", "/ws"), "done", "/ws");
        service.approve(action.id());
        var confirmed = service.confirm(action.id());
        assertEquals(ActionStatus.COMPLETED, confirmed.status());
        assertNotNull(confirmed.resolvedAt());
    }

    @Test
    void cancelReturnsToProposed() {
        var action = service.propose("adv4", ActionCategory.LIFECYCLE, "slot.merge",
                Map.of("slotId", "s1", "workspaceRoot", "/ws"), "done", "/ws");
        service.approve(action.id());
        var cancelled = service.cancel(action.id());
        assertEquals(ActionStatus.PROPOSED, cancelled.status());
    }

    @Test
    void rejectSetsTerminalState() {
        var action = service.propose("adv5", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "x", "/ws");
        var rejected = service.reject(action.id());
        assertEquals(ActionStatus.REJECTED, rejected.status());
        assertNotNull(rejected.resolvedAt());
    }

    @Test
    void invalidTransitionFromTerminalReturnsCurrent() {
        var action = service.propose("adv6", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "x", "/ws");
        service.reject(action.id());
        var result = service.approve(action.id());
        assertNotNull(result);
        assertEquals(ActionStatus.REJECTED, result.status());
    }

    @Test
    void invalidTransitionFromProposedToConfirmReturnsCurrent() {
        var action = service.propose("adv7", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "x", "/ws");
        var result = service.confirm(action.id());
        assertNotNull(result);
        assertEquals(ActionStatus.PROPOSED, result.status());
    }

    @Test
    void expireStaleExpiresMatchingActions() {
        service.propose("adv8", ActionCategory.LIFECYCLE, "slot.merge",
                Map.of("slotId", "s1", "workspaceRoot", "/ws"), "merge it", "/ws");
        service.expireStale("slot.merge", Map.of("slotId", "s1"));
        var pending = service.pendingActions("/ws");
        assertTrue(pending.isEmpty());
    }

    @Test
    void expireStaleDoesNotExpireNonMatchingActions() {
        service.propose("adv9", ActionCategory.LIFECYCLE, "slot.merge",
                Map.of("slotId", "s1", "workspaceRoot", "/ws"), "merge it", "/ws");
        service.expireStale("slot.merge", Map.of("slotId", "s2"));
        var pending = service.pendingActions("/ws");
        assertEquals(1, pending.size());
    }

    @Test
    void expireStaleExpiresConfirmingActions() {
        var action = service.propose("adv10", ActionCategory.LIFECYCLE, "slot.merge",
                Map.of("slotId", "s1", "workspaceRoot", "/ws"), "merge it", "/ws");
        service.approve(action.id());
        service.expireStale("slot.merge", Map.of("slotId", "s1"));
        var pending = service.pendingActions("/ws");
        assertTrue(pending.isEmpty());
    }

    @Test
    void pendingActionsReturnsProposedAndConfirming() {
        service.propose("adv11", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "x", "/ws");
        var a2 = service.propose("adv12", ActionCategory.ADVISORY, "advisory.investigate",
                Map.of("issueKey", "#6"), "y", "/ws");
        service.reject(a2.id());
        var pending = service.pendingActions("/ws");
        assertEquals(1, pending.size());
    }

    @Test
    void actionHistoryReturnsAllStatuses() {
        var a1 = service.propose("adv13", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "x", "/ws");
        service.approve(a1.id());
        service.propose("adv14", ActionCategory.ADVISORY, "advisory.investigate",
                Map.of("issueKey", "#6"), "y", "/ws");
        var history = service.actionHistory("/ws", 10);
        assertEquals(2, history.size());
    }

    @Test
    void getActionReturnsNullForUnknown() {
        assertNull(service.getAction("nonexistent"));
    }

    @Test
    void getActionReturnsExistingAction() {
        var action = service.propose("adv15", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "x", "/ws");
        var fetched = service.getAction(action.id());
        assertNotNull(fetched);
        assertEquals(action.id(), fetched.id());
    }
}
