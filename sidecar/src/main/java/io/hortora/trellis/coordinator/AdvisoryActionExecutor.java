package io.hortora.trellis.coordinator;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

@ApplicationScoped
public class AdvisoryActionExecutor implements ActionExecutor {

    private static final Set<String> TYPES = Set.of("advisory.prioritise", "advisory.investigate");

    @Override
    public ActionCategory category() { return ActionCategory.ADVISORY; }

    @Override
    public Set<String> supportedTypes() { return TYPES; }

    @Override
    public ActionResult execute(ProposedAction action) {
        return ActionResult.ok("Acknowledged: " + action.actionType());
    }
}
