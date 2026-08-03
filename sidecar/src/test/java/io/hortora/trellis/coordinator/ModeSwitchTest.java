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

class ModeSwitchTest {

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
                    "autonomyLevel": { "/ws": "OBSERVATION" },
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
    void switchToManualCancelsCountdowns() {
        autonomyResolver.setSessionOverride(AutonomyLevel.OBSERVATION);
        var a1 = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#1"), "ready", "/ws");
        var a2 = service.propose("adv2", ActionCategory.ADVISORY, "advisory.investigate",
                Map.of("issueKey", "#2"), "ready", "/ws");
        assertTrue(countdownScheduler.hasCountdown(a1.id()));
        assertTrue(countdownScheduler.hasCountdown(a2.id()));

        service.cancelAllCountdowns();

        assertFalse(countdownScheduler.hasCountdown(a1.id()));
        assertFalse(countdownScheduler.hasCountdown(a2.id()));
        assertNull(service.getAction(a1.id()).countdownEndsAt());
        assertNull(service.getAction(a2.id()).countdownEndsAt());
        assertEquals(ActionStatus.PROPOSED, service.getAction(a1.id()).status());
        assertEquals(ActionStatus.PROPOSED, service.getAction(a2.id()).status());
    }

    @Test
    void switchFromManualDoesNotRetroact() {
        autonomyResolver.setSessionOverride(AutonomyLevel.MANUAL);
        var a1 = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#1"), "ready", "/ws");
        var a2 = service.propose("adv2", ActionCategory.ADVISORY, "advisory.investigate",
                Map.of("issueKey", "#2"), "ready", "/ws");
        assertFalse(countdownScheduler.hasCountdown(a1.id()));
        assertFalse(countdownScheduler.hasCountdown(a2.id()));

        // Switch to OBSERVATION — existing PROPOSED actions should NOT get countdowns
        autonomyResolver.setSessionOverride(AutonomyLevel.OBSERVATION);
        assertFalse(countdownScheduler.hasCountdown(a1.id()));
        assertFalse(countdownScheduler.hasCountdown(a2.id()));
    }
}
