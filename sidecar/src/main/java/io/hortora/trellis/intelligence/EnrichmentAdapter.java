package io.hortora.trellis.intelligence;

import io.cloudevents.CloudEvent;
import io.hortora.trellis.worklog.BacklogEntry;
import io.hortora.trellis.worklog.WorklogService;
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

    private final WorklogService worklogService;
    private final Event<CloudEvent> cloudEventBus;

    @Inject
    public EnrichmentAdapter(WorklogService worklogService, Event<CloudEvent> cloudEventBus) {
        this.worklogService = worklogService;
        this.cloudEventBus = cloudEventBus;
    }

    public void emitIssueEvents() {
        if (!worklogService.isDbAvailable()) return;
        var entries = worklogService.backlogEntries(null);
        for (BacklogEntry entry : entries) {
            var blockedBy = extractBlockers(entry);
            var data = Map.<String, Object>of(
                    "issueNumber", entry.issueNumber(),
                    "issueRepo", entry.issueRepo(),
                    "state", "OPEN",
                    "title", entry.title() != null ? entry.title() : "",
                    "blockedBy", blockedBy
            );
            if (cloudEventBus != null) {
                cloudEventBus.fireAsync(TrellisCloudEvents.enrichmentIssue(data));
            }
        }
    }

    static List<Map<String, Object>> extractBlockers(BacklogEntry entry) {
        var blockers = new ArrayList<Map<String, Object>>();
        if (entry.labels() == null) return blockers;
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
