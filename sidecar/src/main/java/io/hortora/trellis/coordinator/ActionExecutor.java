package io.hortora.trellis.coordinator;

import java.util.Set;

public interface ActionExecutor {
    ActionCategory category();
    Set<String> supportedTypes();
    ActionResult execute(ProposedAction action);
}
