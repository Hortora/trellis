package io.hortora.trellis.scanner;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class SubsystemHealthMonitor {

    private static final Logger LOG = Logger.getLogger(SubsystemHealthMonitor.class);
    private static final Path STATE_DIR = Path.of(System.getProperty("user.home"), ".trellis");
    private static final Path STATE_FILE = STATE_DIR.resolve("watched-roots.json");

    @Inject FileWatcherService watcherService;

    void onStartup(@Observes StartupEvent event) {
        var roots = loadPersistedRoots();
        if (roots.isEmpty()) return;
        LOG.infof("Restoring %d workspace watcher(s) from persisted state", roots.size());
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                Thread.ofVirtual().name("health-restore-" + root.getFileName()).start(() -> {
                    try {
                        watcherService.watch(root);
                        LOG.infof("Restored watcher for %s", root);
                    } catch (Exception e) {
                        LOG.warnf(e, "Failed to restore watcher for %s", root);
                    }
                });
            }
        }
    }

    @Scheduled(every = "30s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void healthCheck() {
        var persisted = loadPersistedRoots();
        var active = watcherService.getWatchedRoots();
        int restored = 0;
        for (Path root : persisted) {
            if (!active.contains(root) && Files.isDirectory(root)) {
                LOG.infof("Health check: watcher missing for %s — restarting", root);
                try {
                    watcherService.watch(root);
                    restored++;
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to restart watcher for %s", root);
                }
            }
        }
        if (restored > 0) {
            LOG.infof("Health check: restored %d watcher(s)", restored);
        }
        if (!active.equals(Set.copyOf(persisted))) {
            persistRoots(active);
        }
    }

    private List<Path> loadPersistedRoots() {
        if (!Files.isRegularFile(STATE_FILE)) return List.of();
        try (var reader = Json.createReader(Files.newInputStream(STATE_FILE))) {
            JsonArray arr = reader.readArray();
            var roots = new ArrayList<Path>(arr.size());
            for (JsonValue v : arr) {
                if (v instanceof JsonString s) {
                    roots.add(Path.of(s.getString()));
                }
            }
            return roots;
        } catch (Exception e) {
            LOG.warnf(e, "Failed to read %s", STATE_FILE);
            return List.of();
        }
    }

    private void persistRoots(Set<Path> roots) {
        try {
            Files.createDirectories(STATE_DIR);
            var builder = Json.createArrayBuilder();
            for (Path r : roots) {
                builder.add(r.toString());
            }
            Files.writeString(STATE_FILE, builder.build().toString());
        } catch (IOException e) {
            LOG.warnf(e, "Failed to persist watched roots to %s", STATE_FILE);
        }
    }
}
