package io.hortora.trellis.mcp;

import io.hortora.trellis.scanner.FileWatcherService;
import io.hortora.trellis.scanner.SlotStatus;
import io.hortora.trellis.scanner.WorkspaceModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class WorkspaceModelProvider implements ModelProvider {

    @Inject
    FileWatcherService fileWatcher;

    @Override
    public String domain() {
        return "workspace";
    }

    @Override
    public Object summary() {
        var models = fileWatcher.allModels();
        if (models.isEmpty()) return Map.of();
        var model = models.getFirst();
        var map = new LinkedHashMap<String, Object>();
        map.put("root", model.root().toString());
        map.put("slotCount", model.slots().size());
        var activeSlot = model.slots().stream()
                .filter(s -> s.status() == SlotStatus.ACTIVE)
                .findFirst();
        map.put("activeSlot", activeSlot.map(s -> "slot-" + s.number()).orElse(null));
        return map;
    }

    @Override
    public Object resolve(String subpath) {
        var models = fileWatcher.allModels();
        if (models.isEmpty()) return null;
        var model = models.getFirst();
        if (subpath == null || subpath.isEmpty()) {
            return fullModel(model);
        }
        return switch (subpath.split("/")[0]) {
            case "repos" -> model.repos();
            case "slots" -> model.slots();
            case "epics" -> model.epics();
            case "pauses" -> model.pauses();
            default -> null;
        };
    }

    @Override
    public List<ActionDescriptor> actionsFor(String nodeType) {
        return List.of();
    }

    private Map<String, Object> fullModel(WorkspaceModel model) {
        var map = new LinkedHashMap<String, Object>();
        map.put("root", model.root().toString());
        map.put("scannedAt", model.scannedAt().toString());
        map.put("repos", model.repos());
        map.put("slots", model.slots());
        map.put("epics", model.epics());
        map.put("pauses", model.pauses());
        return map;
    }
}
