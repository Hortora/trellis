package io.hortora.trellis.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class UIStateModelProvider implements ModelProvider {

    private static final long STALENESS_THRESHOLD_MS = 30_000;

    @Inject
    UIStateStore store;

    @Override
    public String domain() {
        return "ui";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object summary() {
        var state = store.current();
        if (state == null) {return Map.of("connected", false);}
        var result = new LinkedHashMap<String, Object>(state);
        if (result.get("panels") instanceof Map<?, ?> panels) {
            var annotated = new LinkedHashMap<String, Object>();
            for (var entry : panels.entrySet()) {
                var key = (String) entry.getKey();
                if (entry.getValue() instanceof Map<?, ?> panel) {
                    var copy       = new LinkedHashMap<String, Object>((Map<String, Object>) panel);
                    var lastPushed = panel.get("lastPushed");
                    if (lastPushed instanceof Number ts) {
                        boolean stale = Instant.now().toEpochMilli() - ts.longValue() > STALENESS_THRESHOLD_MS;
                        copy.put("stale", stale);
                    }
                    annotated.put(key, copy);
                } else {
                    annotated.put(key, entry.getValue());
                }
            }
            result.put("panels", annotated);
        }
        return result;
    }

    @Override
    public Object resolve(String subpath) {
        var state = store.current();
        if (state == null) return null;
        if (subpath == null || subpath.isEmpty()) return summary();
        if (subpath.startsWith("dock-bar")) {
            return state.get("activePanel");
        }
        if (subpath.startsWith("panels/")) {
            var panelName = subpath.substring("panels/".length()).split("/")[0];
            if (state.get("panels") instanceof Map<?, ?> panels) {
                return panels.get(panelName);
            }
        }
        return null;
    }

    @Override
    public List<ActionDescriptor> actionsFor(String nodeType) {
        return List.of();
    }
}
