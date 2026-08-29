package io.hortora.trellis.intelligence;

import io.cloudevents.CloudEvent;
import io.hortora.trellis.worklog.WorklogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@ApplicationScoped
public class WorklogEventAdapter {

    private final WorklogService worklogService;
    private final Event<CloudEvent> cloudEventBus;

    @Inject
    public WorklogEventAdapter(WorklogService worklogService, Event<CloudEvent> cloudEventBus) {
        this.worklogService = worklogService;
        this.cloudEventBus = cloudEventBus;
    }

    WorklogEventAdapter(WorklogService worklogService) {
        this.worklogService = worklogService;
        this.cloudEventBus = null;
    }

    public void emitSnapshots() {
        if (!worklogService.isDbAvailable()) return;
        var items = worklogService.activeWork();
        var now = Instant.now();
        for (var item : items) {
            var timeline = worklogService.workItemTimeline(item.branch(), item.repoPath());
            String lastTimestamp = timeline.isEmpty() ? item.createdAt()
                    : timeline.getLast().timestamp();
            long daysAgo = parseDaysAgo(lastTimestamp, now);

            int issueNumber = item.issues().stream()
                    .filter(i -> i.isPrimary())
                    .mapToInt(i -> i.issueNumber())
                    .findFirst()
                    .orElse(item.issues().isEmpty() ? 0 : item.issues().getFirst().issueNumber());

            var data = Map.<String, Object>of(
                    "branch", item.branch(),
                    "issueNumber", issueNumber,
                    "lastEventDaysAgo", daysAgo,
                    "state", item.state()
            );

            if (cloudEventBus != null) {
                cloudEventBus.fireAsync(TrellisCloudEvents.worklogSnapshot(data));
            }
        }
    }

    static long parseDaysAgo(String timestamp, Instant now) {
        try {
            var eventTime = Instant.parse(timestamp);
            return Duration.between(eventTime, now).toDays();
        } catch (Exception e) {
            return 0;
        }
    }
}
