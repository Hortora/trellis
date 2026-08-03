package io.hortora.trellis.coordinator;

import io.hortora.trellis.config.PreferencesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ActionServiceAutonomyTest {

    @TempDir Path tmpDir;

    DataSource dataSource;
    ActionService service;
    CountdownScheduler countdownScheduler;
    AutonomyResolver autonomyResolver;
    PreferencesService preferences;

    @BeforeEach
    void setUp() throws Exception {
        var sds = new SQLiteDataSource();
        sds.setUrl("jdbc:sqlite:" + tmpDir.resolve("test-" + System.nanoTime() + ".db"));
        dataSource = sds;
        new CoordinatorSchemaManager().initialize(dataSource);

        countdownScheduler = new CountdownScheduler();

        var prefsPath = tmpDir.resolve("preferences.json");
        Files.writeString(prefsPath, """
                {
                  "coordinator": {
                    "autonomyLevel": { "/ws": "MANUAL" },
                    "observationCountdownSeconds": 2,
                    "rateLimitMaxActions": 5,
                    "rateLimitWindowSeconds": 60
                  }
                }
                """);
        preferences = new PreferencesService(prefsPath);
        autonomyResolver = AutonomyResolver.forTest(preferences);

        var advisoryExecutor = new AdvisoryActionExecutor();
        service = ActionService.forTest(dataSource, List.of(advisoryExecutor),
                autonomyResolver, countdownScheduler, preferences);
    }

    @AfterEach
    void tearDown() {
        countdownScheduler.shutdown();
    }

    @Test
    void proposeInManualLeavesProposed() {
        autonomyResolver.setSessionOverride(AutonomyLevel.MANUAL);
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "ready", "/ws");
        assertEquals(ActionStatus.PROPOSED, service.getAction(action.id()).status());
        assertFalse(countdownScheduler.hasCountdown(action.id()));
    }

    @Test
    void proposeAutonomousLowRiskAutoExecutes() {
        autonomyResolver.setSessionOverride(AutonomyLevel.AUTONOMOUS);
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "ready", "/ws");
        var fetched = service.getAction(action.id());
        assertEquals(ActionStatus.COMPLETED, fetched.status());
    }

    @Test
    void proposeAutonomousHighRiskSchedulesCountdown() {
        autonomyResolver.setSessionOverride(AutonomyLevel.AUTONOMOUS);
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "ready", "/ws");
        // advisory.prioritise is LOW risk — use a GATED override to test high-risk path
        // Need to test with a GATED action type instead
        // This test uses lifecycle.end which is HIGH risk
        var highRiskAction = service.propose("adv2", ActionCategory.ADVISORY, "lifecycle.end",
                Map.of("slotId", "s1"), "ready", "/ws");
        // lifecycle.end is HIGH risk → GATED policy → countdown
        assertTrue(countdownScheduler.hasCountdown(highRiskAction.id()));
        assertEquals(ActionStatus.PROPOSED, service.getAction(highRiskAction.id()).status());
    }

    @Test
    void proposeObservationSchedulesCountdown() {
        autonomyResolver.setSessionOverride(AutonomyLevel.OBSERVATION);
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "ready", "/ws");
        assertTrue(countdownScheduler.hasCountdown(action.id()));
        assertEquals(ActionStatus.PROPOSED, service.getAction(action.id()).status());
        assertNotNull(service.getAction(action.id()).countdownEndsAt());
    }

    @Test
    void countdownEndsAtPersistedOnSchedule() {
        autonomyResolver.setSessionOverride(AutonomyLevel.OBSERVATION);
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "ready", "/ws");
        var fetched = service.getAction(action.id());
        assertNotNull(fetched.countdownEndsAt());
    }

    @Test
    void autoExecuteSkipsRiskGate() {
        // lifecycle.end is HIGH risk — manual approve() would go to CONFIRMING
        // autoExecute should skip the risk gate and go straight to APPROVED→EXECUTING→COMPLETED/FAILED
        autonomyResolver.setSessionOverride(AutonomyLevel.MANUAL);
        var lifecycleExecutor = new LifecycleActionExecutorTest.StubLifecycleManager(
                new io.hortora.trellis.lifecycle.OperationResult(true, 0, Map.of(), "done"));
        var svc = ActionService.forTest(dataSource,
                List.of(new LifecycleActionExecutor(lifecycleExecutor)),
                autonomyResolver, countdownScheduler, preferences);

        var action = svc.propose("adv1", ActionCategory.LIFECYCLE, "lifecycle.end",
                Map.of("slotId", "s1", "workspaceRoot", "/ws"), "ready", "/ws");
        assertEquals(ActionStatus.PROPOSED, svc.getAction(action.id()).status());

        svc.autoExecute(action.id());
        var fetched = svc.getAction(action.id());
        assertEquals(ActionStatus.COMPLETED, fetched.status());
    }

    @Test
    void autoExecuteCasPreventsDuplicateExecution() {
        autonomyResolver.setSessionOverride(AutonomyLevel.MANUAL);
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "ready", "/ws");
        service.reject(action.id());

        service.autoExecute(action.id());
        assertEquals(ActionStatus.REJECTED, service.getAction(action.id()).status());
    }

    @Test
    void proposeWithNullResolverLeavesProposed() {
        // Backwards-compat: ActionService without autonomy resolver acts as MANUAL
        var svc = ActionService.forTest(dataSource, List.of(new AdvisoryActionExecutor()));
        var action = svc.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "ready", "/ws");
        assertEquals(ActionStatus.PROPOSED, svc.getAction(action.id()).status());
    }
}
