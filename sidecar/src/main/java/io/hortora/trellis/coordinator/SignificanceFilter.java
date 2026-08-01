package io.hortora.trellis.coordinator;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class SignificanceFilter {

    public boolean isSignificant(List<CoordinatorEvent> batch) {
        if (batch.isEmpty()) return false;
        for (var event : batch) {
            if (event instanceof CoordinatorEvent.AnalysisEvent ae && !ae.newlyUnblocked().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
