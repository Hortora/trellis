package io.hortora.trellis.coordinator;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

@ApplicationScoped
public class AgentActionExecutor implements ActionExecutor {

    private static final Set<String> TYPES = Set.of(
            "agent.start", "agent.stop", "agent.pause", "agent.resume", "agent.refresh");

    @Override
    public ActionCategory category() { return ActionCategory.AGENT; }

    @Override
    public Set<String> supportedTypes() { return TYPES; }

    @Override
    public ActionResult execute(ProposedAction action) {
        return ActionResult.fail("Agent management not yet implemented (issue #20)");
    }
}
