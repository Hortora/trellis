package io.hortora.trellis.intelligence;

import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class DeferredItemAdapter {

    private static final Pattern DEFERRED_PATTERN = Pattern.compile(
            "^\\s*-\\s+(.+?)\\s*\\(([^)]+)\\)\\s*(?:—\\s*(.+))?$");

    private final Event<CloudEvent> cloudEventBus;

    @Inject
    public DeferredItemAdapter(Event<CloudEvent> cloudEventBus) {
        this.cloudEventBus = cloudEventBus;
    }

    DeferredItemAdapter() {
        this.cloudEventBus = null;
    }

    public void emitDeferredItems(List<Path> planFiles) {
        var now = Instant.now();
        for (Path planFile : planFiles) {
            var items = parseDeferredItems(planFile, now);
            for (var data : items) {
                if (cloudEventBus != null) {
                    cloudEventBus.fireAsync(TrellisCloudEvents.deferredItem(data));
                }
            }
        }
    }

    static List<Map<String, Object>> parseDeferredItems(Path planFile, Instant now) {
        var items = new ArrayList<Map<String, Object>>();
        try {
            var lines = Files.readAllLines(planFile);
            boolean inDeferred = false;
            for (String line : lines) {
                if (line.trim().equalsIgnoreCase("## deferred") || line.trim().equalsIgnoreCase("## deferred items")) {
                    inDeferred = true;
                    continue;
                }
                if (inDeferred && line.startsWith("## ")) {
                    break;
                }
                if (inDeferred) {
                    Matcher m = DEFERRED_PATTERN.matcher(line);
                    if (m.matches()) {
                        String title = m.group(1).trim();
                        String meta = m.group(2).trim();
                        String reason = m.group(3) != null ? m.group(3).trim() : "";

                        long deferredDaysAgo = estimateDeferralAge(planFile, now);
                        items.add(Map.of(
                                "title", title,
                                "reason", reason,
                                "blockerState", "",
                                "deferredDaysAgo", deferredDaysAgo,
                                "source", planFile.toString()
                        ));
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return items;
    }

    private static long estimateDeferralAge(Path planFile, Instant now) {
        try {
            var mtime = Files.getLastModifiedTime(planFile).toInstant();
            return Duration.between(mtime, now).toDays();
        } catch (IOException e) {
            return 0;
        }
    }
}
