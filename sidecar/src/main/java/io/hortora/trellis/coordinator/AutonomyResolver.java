package io.hortora.trellis.coordinator;

import io.hortora.trellis.config.PreferencesService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AutonomyResolver {
    @Inject PreferencesService preferences;

    private volatile AutonomyLevel sessionOverride;

    AutonomyResolver() {}

    static AutonomyResolver forTest(PreferencesService preferences) {
        var r = new AutonomyResolver();
        r.preferences = preferences;
        return r;
    }

    public AutonomyLevel resolveLevel(String workspace) {
        if (sessionOverride != null) return sessionOverride;
        return preferences.autonomyLevel(workspace);
    }

    public AutonomyOverride resolvePolicy(String actionType) {
        var override = preferences.autonomyOverride(actionType);
        if (override != null) return override;
        return RiskClassification.riskFor(actionType) == RiskLevel.LOW
                ? AutonomyOverride.AUTONOMOUS : AutonomyOverride.GATED;
    }

    public void setSessionOverride(AutonomyLevel level) {
        this.sessionOverride = level;
    }

    public void clearSessionOverride() {
        this.sessionOverride = null;
    }

    public AutonomyLevel sessionOverride() {
        return sessionOverride;
    }
}
