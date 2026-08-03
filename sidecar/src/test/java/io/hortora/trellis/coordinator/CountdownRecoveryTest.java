package io.hortora.trellis.coordinator;

import io.hortora.trellis.config.PreferencesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CountdownRecoveryTest {

    @TempDir Path tmpDir;

    DataSource dataSource;
    ActionService service;
    CountdownScheduler countdownScheduler;
    AutonomyResolver autonomyResolver;

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
                    "observationCountdownSeconds": 30
                  }
                }
                """);
        var preferences = new PreferencesService(prefsPath);
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
    void pastDeadlineAutoExecutesOnRecovery() {
        autonomyResolver.setSessionOverride(AutonomyLevel.MANUAL);
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#1"), "ready", "/ws");
        // Manually set deadline in the past
        setCountdownDeadline(action.id(), Instant.now().minusSeconds(60));

        service.recoverCountdowns("/ws");
        assertEquals(ActionStatus.COMPLETED, service.getAction(action.id()).status());
    }

    @Test
    void futureDeadlineRescheduled() {
        autonomyResolver.setSessionOverride(AutonomyLevel.MANUAL);
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#1"), "ready", "/ws");
        setCountdownDeadline(action.id(), Instant.now().plusSeconds(20));

        service.recoverCountdowns("/ws");
        assertTrue(countdownScheduler.hasCountdown(action.id()));
        assertEquals(ActionStatus.PROPOSED, service.getAction(action.id()).status());
    }

    @Test
    void nullDeadlineSkipped() {
        autonomyResolver.setSessionOverride(AutonomyLevel.MANUAL);
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#1"), "ready", "/ws");
        // No countdown_ends_at set
        service.recoverCountdowns("/ws");
        assertFalse(countdownScheduler.hasCountdown(action.id()));
        assertEquals(ActionStatus.PROPOSED, service.getAction(action.id()).status());
    }

    @Test
    void terminalStateSkipped() {
        autonomyResolver.setSessionOverride(AutonomyLevel.MANUAL);
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#1"), "ready", "/ws");
        service.reject(action.id());
        setCountdownDeadline(action.id(), Instant.now().minusSeconds(10));

        service.recoverCountdowns("/ws");
        assertEquals(ActionStatus.REJECTED, service.getAction(action.id()).status());
    }

    private void setCountdownDeadline(String actionId, Instant deadline) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE coordinator_actions SET countdown_ends_at = ? WHERE id = ?")) {
            ps.setString(1, deadline.toString());
            ps.setString(2, actionId);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
