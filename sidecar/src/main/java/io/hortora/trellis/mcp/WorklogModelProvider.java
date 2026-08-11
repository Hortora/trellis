package io.hortora.trellis.mcp;

import io.hortora.trellis.scanner.FileWatcherService;
import io.hortora.trellis.worklog.WorklogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

@ApplicationScoped
public class WorklogModelProvider implements ModelProvider {

    private final WorklogService worklogService;
    private final FileWatcherService fileWatcher;

    @Inject
    public WorklogModelProvider(WorklogService worklogService, FileWatcherService fileWatcher) {
        this.worklogService = worklogService;
        this.fileWatcher = fileWatcher;
    }

    @Override
    public String domain() {
        return "worklog";
    }

    @Override
    public Object summary() {
        var root = resolveWorkspaceRoot();
        var s = worklogService.summary(root);
        var map = new LinkedHashMap<String, Object>();
        map.put("activeWorkItems", s.activeWorkItems());
        map.put("recentEventCount", s.recentEventCount());
        if (s.latestEvent() != null) {
            var event = new LinkedHashMap<String, Object>();
            event.put("type", s.latestEvent().eventType());
            event.put("timestamp", s.latestEvent().timestamp());
            map.put("latestEvent", event);
        }
        if (s.planPosition() != null) {
            var plan = new LinkedHashMap<String, Object>();
            plan.put("active", s.planPosition().activeIssue());
            plan.put("completed", s.planPosition().completed() + "/" + s.planPosition().total());
            map.put("planPosition", plan);
        }
        map.put("slotsActive", s.slotsActive());
        return map;
    }

    @Override
    public Object resolve(String subpath) {
        if (subpath == null || subpath.isEmpty()) return summary();
        return switch (subpath.split("/")[0]) {
            case "events" -> worklogService.recentEvents(null, null, 50);
            case "work-items" -> worklogService.activeWork();
            case "slots" -> worklogService.slotStatus(null);
            case "backlog" -> worklogService.backlogEntries(null);
            default -> null;
        };
    }

    @Override
    public List<ActionDescriptor> actionsFor(String nodeType) {
        return List.of();
    }

    private Path resolveWorkspaceRoot() {
        if (fileWatcher == null) return null;
        var models = fileWatcher.allModels();
        return models.isEmpty() ? null : models.getFirst().root();
    }
}
