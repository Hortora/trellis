package io.hortora.trellis.scanner;

import io.casehub.pages.push.EventBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class FileWatcherService {

    private static final Logger LOG = Logger.getLogger(FileWatcherService.class);
    private static final long RESCAN_INTERVAL_SECONDS = 60;
    private static final String TOPIC_REPOS = "workspace:repos";
    private static final String TOPIC_SLOTS = "workspace:slots";

    @Inject
    WorkspaceScanner scanner;

    @Inject
    EventBroadcaster broadcaster;

    private final ConcurrentHashMap<Path, WatchState> watches = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "trellis-watcher"); t.setDaemon(true); return t; });

    public void watch(Path root) {
        watches.computeIfAbsent(root, r -> {
            var state = new WatchState(r, scanner.scan(r));
            startWatcher(state);
            startRescanFallback(state);
            return state;
        });
    }

    public void stopWatching(Path root) {
        var state = watches.remove(root);
        if (state != null) state.stop();
    }

    public WorkspaceModel currentModel(Path root) {
        var state = watches.get(root);
        return state != null ? state.model : null;
    }

    public void onWorkspaceChanged(@Observes @WorkspaceChanged Path root) {
        rescan(root);
    }

    private void rescan(Path root) {
        var state = watches.get(root);
        if (state == null) return;

        var newModel = scanner.scan(root);
        var oldModel = state.model;
        state.model = newModel;

        if (!oldModel.repos().equals(newModel.repos())) {
            broadcaster.broadcast(TOPIC_REPOS, newModel.repos());
        }
        if (!oldModel.slots().equals(newModel.slots())) {
            broadcaster.broadcast(TOPIC_SLOTS, newModel.slots());
        }
    }

    private void startWatcher(WatchState state) {
        Thread.ofVirtual().name("trellis-fswatcher-" + state.root.getFileName()).start(() -> {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                state.root.register(watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);

                Path worktreesDir = state.root.resolve("worktrees");
                if (Files.isDirectory(worktreesDir)) {
                    worktreesDir.register(watcher,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE);
                }

                state.watchService = watcher;

                while (!state.stopped) {
                    WatchKey key;
                    try {
                        key = watcher.poll(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    if (key == null) continue;

                    boolean changed = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            changed = true;
                            break;
                        }
                        changed = true;
                    }

                    key.reset();

                    if (changed) {
                        rescan(state.root);
                    }
                }
            } catch (IOException e) {
                LOG.warnf(e, "File watcher failed for %s — fallback rescan will continue", state.root);
            }
        });
    }

    private void startRescanFallback(WatchState state) {
        scheduler.scheduleAtFixedRate(() -> {
            if (state.stopped) return;
            try {
                rescan(state.root);
            } catch (Exception e) {
                LOG.warnf(e, "Rescan failed for %s", state.root);
            }
        }, RESCAN_INTERVAL_SECONDS, RESCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private static class WatchState {
        final Path root;
        volatile WorkspaceModel model;
        volatile WatchService watchService;
        volatile boolean stopped;

        WatchState(Path root, WorkspaceModel initialModel) {
            this.root = root;
            this.model = initialModel;
        }

        void stop() {
            stopped = true;
            if (watchService != null) {
                try { watchService.close(); } catch (IOException ignored) {}
            }
        }
    }
}
