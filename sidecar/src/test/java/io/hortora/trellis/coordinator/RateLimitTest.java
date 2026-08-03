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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitTest {

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
                    "autonomyLevel": { "/ws": "AUTONOMOUS" },
                    "observationCountdownSeconds": 2,
                    "rateLimitMaxActions": 3,
                    "rateLimitWindowSeconds": 60
                  }
                }
                """);
        var preferences = new PreferencesService(prefsPath);
        autonomyResolver = AutonomyResolver.forTest(preferences);
        autonomyResolver.setSessionOverride(AutonomyLevel.AUTONOMOUS);

        var advisoryExecutor = new AdvisoryActionExecutor();
        service = ActionService.forTest(dataSource, List.of(advisoryExecutor),
                autonomyResolver, countdownScheduler, preferences);
    }

    @AfterEach
    void tearDown() {
        countdownScheduler.shutdown();
    }

    @Test
    void withinLimitProceeds() {
        for (int i = 0; i < 3; i++) {
            var action = service.propose("adv" + i, ActionCategory.ADVISORY, "advisory.prioritise",
                    Map.of("issueKey", "#" + i), "ready", "/ws");
            assertEquals(ActionStatus.COMPLETED, service.getAction(action.id()).status(),
                    "Action " + i + " should auto-execute");
        }
    }

    @Test
    void exceedingLimitFallsToCountdown() {
        for (int i = 0; i < 3; i++) {
            service.propose("adv" + i, ActionCategory.ADVISORY, "advisory.prioritise",
                    Map.of("issueKey", "#" + i), "ready", "/ws");
        }
        var action = service.propose("adv3", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#3"), "ready", "/ws");
        assertEquals(ActionStatus.PROPOSED, service.getAction(action.id()).status());
        assertTrue(countdownScheduler.hasCountdown(action.id()));
    }

    @Test
    void manualApprovalResetsTimestamps() {
        for (int i = 0; i < 3; i++) {
            service.propose("adv" + i, ActionCategory.ADVISORY, "advisory.prioritise",
                    Map.of("issueKey", "#" + i), "ready", "/ws");
        }
        // Rate limit reached — next would get countdown
        // Reset via manual approval simulation
        service.resetRateLimit();

        var action = service.propose("adv4", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#4"), "ready", "/ws");
        assertEquals(ActionStatus.COMPLETED, service.getAction(action.id()).status());
    }
}
