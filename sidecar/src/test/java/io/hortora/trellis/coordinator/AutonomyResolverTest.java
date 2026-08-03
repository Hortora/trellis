package io.hortora.trellis.coordinator;

import io.hortora.trellis.config.PreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AutonomyResolverTest {

    @TempDir Path tempDir;
    private AutonomyResolver resolver;

    @BeforeEach
    void setUp() throws IOException {
        var prefs = tempDir.resolve("preferences.json");
        Files.writeString(prefs, """
            {
              "coordinator": {
                "autonomyLevel": {
                  "/ws/test": "OBSERVATION"
                },
                "autonomyOverrides": {
                  "slot.create": "GATED",
                  "lifecycle.end": "AUTONOMOUS"
                }
              }
            }
            """);
        resolver = AutonomyResolver.forTest(new PreferencesService(prefs));
    }

    @Test
    void riskBasedDefaults() {
        assertEquals(AutonomyOverride.AUTONOMOUS, resolver.resolvePolicy("lifecycle.pause"));
        assertEquals(AutonomyOverride.GATED, resolver.resolvePolicy("lifecycle.start"));
    }

    @Test
    void overridePromotesLowToGated() {
        assertEquals(AutonomyOverride.GATED, resolver.resolvePolicy("slot.create"));
    }

    @Test
    void overrideDemotesHighToAutonomous() {
        assertEquals(AutonomyOverride.AUTONOMOUS, resolver.resolvePolicy("lifecycle.end"));
    }

    @Test
    void missingOverrideFallsToRisk() {
        assertEquals(AutonomyOverride.AUTONOMOUS, resolver.resolvePolicy("epic.next"));
    }

    @Test
    void sessionOverrideTakesPrecedence() {
        resolver.setSessionOverride(AutonomyLevel.AUTONOMOUS);
        assertEquals(AutonomyLevel.AUTONOMOUS, resolver.resolveLevel("/ws/test"));
    }

    @Test
    void clearSessionOverrideReturnsToPreference() {
        resolver.setSessionOverride(AutonomyLevel.AUTONOMOUS);
        resolver.clearSessionOverride();
        assertEquals(AutonomyLevel.OBSERVATION, resolver.resolveLevel("/ws/test"));
    }

    @Test
    void nullSessionOverrideUsesPreference() {
        assertNull(resolver.sessionOverride());
        assertEquals(AutonomyLevel.OBSERVATION, resolver.resolveLevel("/ws/test"));
        assertEquals(AutonomyLevel.MANUAL, resolver.resolveLevel("/ws/unknown"));
    }
}
