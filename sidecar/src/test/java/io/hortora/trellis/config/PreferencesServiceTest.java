package io.hortora.trellis.config;

import io.hortora.trellis.coordinator.AutonomyLevel;
import io.hortora.trellis.coordinator.AutonomyOverride;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PreferencesServiceTest {

    @TempDir Path tempDir;

    @Test
    void readsValidPreferences() throws IOException {
        var prefs = tempDir.resolve("preferences.json");
        Files.writeString(prefs, """
            {
              "coordinator": {
                "autonomyLevel": {
                  "/ws/a": "OBSERVATION",
                  "/ws/b": "AUTONOMOUS"
                },
                "observationCountdownSeconds": 45,
                "autonomyOverrides": {
                  "slot.create": "GATED",
                  "lifecycle.pause": "AUTONOMOUS"
                },
                "rateLimitMaxActions": 10,
                "rateLimitWindowSeconds": 120
              }
            }
            """);
        var service = new PreferencesService(prefs);

        assertEquals(AutonomyLevel.OBSERVATION, service.autonomyLevel("/ws/a"));
        assertEquals(AutonomyLevel.AUTONOMOUS, service.autonomyLevel("/ws/b"));
        assertEquals(45, service.observationCountdownSeconds());
        assertEquals(AutonomyOverride.GATED, service.autonomyOverride("slot.create"));
        assertEquals(AutonomyOverride.AUTONOMOUS, service.autonomyOverride("lifecycle.pause"));
        assertEquals(10, service.rateLimitMaxActions());
        assertEquals(120, service.rateLimitWindowSeconds());
    }

    @Test
    void defaultsOnMissingFile() {
        var service = new PreferencesService(tempDir.resolve("nonexistent.json"));

        assertEquals(AutonomyLevel.MANUAL, service.autonomyLevel("/ws"));
        assertEquals(30, service.observationCountdownSeconds());
        assertNull(service.autonomyOverride("anything"));
        assertEquals(5, service.rateLimitMaxActions());
        assertEquals(60, service.rateLimitWindowSeconds());
    }

    @Test
    void defaultsOnMalformedJson() throws IOException {
        var prefs = tempDir.resolve("preferences.json");
        Files.writeString(prefs, "{invalid json");
        var service = new PreferencesService(prefs);

        assertEquals(AutonomyLevel.MANUAL, service.autonomyLevel("/ws"));
        assertEquals(30, service.observationCountdownSeconds());
    }

    @Test
    void perWorkspaceAutonomyLevel() throws IOException {
        var prefs = tempDir.resolve("preferences.json");
        Files.writeString(prefs, """
            {
              "coordinator": {
                "autonomyLevel": {
                  "/home/dev/ws1": "AUTONOMOUS",
                  "/home/dev/ws2": "OBSERVATION"
                }
              }
            }
            """);
        var service = new PreferencesService(prefs);

        assertEquals(AutonomyLevel.AUTONOMOUS, service.autonomyLevel("/home/dev/ws1"));
        assertEquals(AutonomyLevel.OBSERVATION, service.autonomyLevel("/home/dev/ws2"));
    }

    @Test
    void unknownWorkspaceDefaultsToManual() throws IOException {
        var prefs = tempDir.resolve("preferences.json");
        Files.writeString(prefs, """
            {
              "coordinator": {
                "autonomyLevel": {
                  "/ws/known": "AUTONOMOUS"
                }
              }
            }
            """);
        var service = new PreferencesService(prefs);

        assertEquals(AutonomyLevel.MANUAL, service.autonomyLevel("/ws/unknown"));
    }
}
