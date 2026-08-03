package io.hortora.trellis.config;

import io.hortora.trellis.coordinator.AutonomyLevel;
import io.hortora.trellis.coordinator.AutonomyOverride;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class PreferencesService {
    private static final Logger LOG = Logger.getLogger(PreferencesService.class);
    private static final Path DEFAULT_PATH =
            Path.of(System.getProperty("user.home"), ".trellis", "preferences.json");

    private volatile JsonObject root = JsonObject.EMPTY_JSON_OBJECT;
    private final Path path;

    public PreferencesService() {
        this(DEFAULT_PATH);
    }

    public PreferencesService(Path path) {
        this.path = path;
        reload();
    }

    public void reload() {
        if (!Files.exists(path)) {
            root = JsonObject.EMPTY_JSON_OBJECT;
            return;
        }
        try (var reader = Json.createReader(Files.newInputStream(path))) {
            root = reader.readObject();
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse %s — using defaults", path);
            root = JsonObject.EMPTY_JSON_OBJECT;
        }
    }

    public AutonomyLevel autonomyLevel(String workspace) {
        var coord = root.getJsonObject("coordinator");
        if (coord == null) return AutonomyLevel.MANUAL;
        var levels = coord.getJsonObject("autonomyLevel");
        if (levels == null) return AutonomyLevel.MANUAL;
        var val = levels.getString(workspace, null);
        if (val == null) return AutonomyLevel.MANUAL;
        try {
            return AutonomyLevel.valueOf(val);
        } catch (IllegalArgumentException e) {
            return AutonomyLevel.MANUAL;
        }
    }

    public AutonomyOverride autonomyOverride(String actionType) {
        var coord = root.getJsonObject("coordinator");
        if (coord == null) return null;
        var overrides = coord.getJsonObject("autonomyOverrides");
        if (overrides == null) return null;
        var val = overrides.getString(actionType, null);
        if (val == null) return null;
        try {
            return AutonomyOverride.valueOf(val);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public int observationCountdownSeconds() {
        return getInt("observationCountdownSeconds", 30);
    }

    public int rateLimitMaxActions() {
        return getInt("rateLimitMaxActions", 5);
    }

    public int rateLimitWindowSeconds() {
        return getInt("rateLimitWindowSeconds", 60);
    }

    private int getInt(String key, int defaultValue) {
        var coord = root.getJsonObject("coordinator");
        if (coord == null) return defaultValue;
        return coord.getInt(key, defaultValue);
    }
}
