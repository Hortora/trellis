package io.hortora.trellis.intelligence;

import io.cloudevents.CloudEvent;
import io.hortora.trellis.dependencies.DependencyService;
import io.hortora.trellis.worklog.BacklogEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class EnrichmentAdapter {

    private static final Pattern BLOCKED_BY_PATTERN = Pattern.compile("#(\\d+)");

    private final DependencyService dependencyService;
    private final Event<CloudEvent> cloudEventBus;

    @Inject
    public EnrichmentAdapter(DependencyService dependencyService, Event<CloudEvent> cloudEventBus) {
        this.dependencyService = dependencyService;
        this.cloudEventBus     = cloudEventBus;
    }

    public void emitIssueEvents(java.nio.file.Path workspaceRoot) {
        var graph = dependencyService.buildGraph(workspaceRoot);
        for (var node : graph.nodes()) {
            var blockerData = node.blockedBy().stream()
                                  .map(ref -> Map.<String, Object>of(
                                          "number", ref.number(),
                                          "state", graph.issueStates().getOrDefault(ref, "EXTERNAL")))
                                  .toList();
            var data = Map.<String, Object>of(
                    "issueNumber", node.ref().number(),
                    "issueRepo", node.ref().repo(),
                    "state", node.issueState(),
                    "title", node.title() != null ? node.title() : "",
                    "blockedBy", blockerData
                                             );
            if (cloudEventBus != null) {
                cloudEventBus.fireAsync(TrellisCloudEvents.enrichmentIssue(data));
            }
        }
    }

    static List<Map<String, Object>> extractBlockers(BacklogEntry entry) {
        var blockers = new ArrayList<Map<String, Object>>();
        if (entry.labels() == null) {return blockers;}
        for (String label : entry.labels()) {
            if (label.toLowerCase().startsWith("blocked")) {
                Matcher m = BLOCKED_BY_PATTERN.matcher(label);
                while (m.find()) {
                    blockers.add(Map.of(
                            "number", Integer.parseInt(m.group(1)),
                            "state", "OPEN"
                                       ));
                }
            }
        }
        return blockers;
    }
}
