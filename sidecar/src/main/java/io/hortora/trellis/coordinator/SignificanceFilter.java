package io.hortora.trellis.coordinator;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class SignificanceFilter {

    private static final int ACTION_EVENT_THRESHOLD = 5;

    public boolean isSignificant(List<CoordinatorEvent> batch) {
        if (batch.isEmpty()) {return false;}

        boolean hasSignificantL1Event = false;
        int     actionEventCount      = 0;

        for (var event : batch) {
            switch (event) {
                case CoordinatorEvent.AnalysisEvent ae when !ae.newlyUnblocked().isEmpty() -> hasSignificantL1Event = true;
                case CoordinatorEvent.ActionStateChangedEvent ase -> {
                    if (!ase.newStatus().isTerminal()) {actionEventCount++;}
                }
                case CoordinatorEvent.LifecycleOperationEvent ignored -> actionEventCount++;
                default -> {}
            }
        }

        if (hasSignificantL1Event) {return true;}
        return actionEventCount > 0 && actionEventCount <= ACTION_EVENT_THRESHOLD;
    }
}
