package io.hortora.trellis.coordinator;

import io.hortora.trellis.lifecycle.ConcurrentOperationException;
import io.hortora.trellis.lifecycle.LifecycleManager;
import io.hortora.trellis.lifecycle.OperationResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class LifecycleActionExecutor implements ActionExecutor {

    private static final Set<String> TYPES = Set.of(
            "lifecycle.start", "lifecycle.end", "lifecycle.pause", "lifecycle.resume",
            "slot.create", "slot.merge", "epic.setup", "epic.next");

    private final LifecycleManager manager;

    @Inject
    public LifecycleActionExecutor(LifecycleManager manager) {
        this.manager = manager;
    }

    @Override
    public ActionCategory category() { return ActionCategory.LIFECYCLE; }

    @Override
    public Set<String> supportedTypes() { return TYPES; }

    @Override
    public ActionResult execute(ProposedAction action) {
        try {
            var result = dispatch(action);
            return new ActionResult(result.success(), result.exitCode(), result.output(), result.stderr());
        } catch (ConcurrentOperationException e) {
            return ActionResult.fail("Concurrent operation: " + e.getMessage());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return ActionResult.fail(e.getMessage());
        }
    }

    private OperationResult dispatch(ProposedAction action)
            throws IOException, InterruptedException, ConcurrentOperationException {
        var p = action.params();
        return switch (action.actionType()) {
            case "lifecycle.start" -> manager.start(
                    Path.of(p.get("workspaceRoot")), p.get("branch"), p.get("issue"));
            case "lifecycle.end" -> manager.end(p.get("slotId"), Path.of(p.get("workspaceRoot")));
            case "lifecycle.pause" -> manager.pause(p.get("slotId"), Path.of(p.get("workspaceRoot")));
            case "lifecycle.resume" -> manager.resume(p.get("slotId"), Path.of(p.get("workspaceRoot")));
            case "slot.create" -> manager.slotCreate(Path.of(p.get("workspaceRoot")), collectListParams(p));
            case "slot.merge" -> manager.slotMerge(p.get("slotId"), Path.of(p.get("workspaceRoot")));
            case "epic.setup" -> manager.epicSetup(Path.of(p.get("workspaceRoot")), collectListParams(p));
            case "epic.next" -> manager.epicNext(p.get("epicPath"));
            default -> throw new IllegalArgumentException("Unknown action type: " + action.actionType());
        };
    }

    private List<String> collectListParams(Map<String, String> params) {
        var args = new ArrayList<String>();
        for (int i = 0; params.containsKey("args." + i); i++) {
            args.add(params.get("args." + i));
        }
        return args;
    }
}
