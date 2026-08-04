package io.hortora.trellis.scanner;

import io.casehub.pages.push.EventBroadcaster;
import io.methvin.watcher.DirectoryWatcher;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class FileWatcherService {

    private static final Logger LOG                     = Logger.getLogger(FileWatcherService.class);
    private static final long   RESCAN_INTERVAL_SECONDS = 60;
    private static final String TOPIC_REPOS             = "workspace:repos";
    private static final String TOPIC_SLOTS             = "workspace:slots";
    private static final String TOPIC_PROTOCOLS         = "workspace:protocols";

    @Inject
    WorkspaceScanner scanner;

    @Inject
    EventBroadcaster broadcaster;

    private final ConcurrentHashMap<Path, WatchState> watches   = new ConcurrentHashMap<>();
    private final ScheduledExecutorService            scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "trellis-watcher-fallback");
                t.setDaemon(true);
                return t;
            });

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
        if (state != null) {state.stop();}
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
        watches.values().forEach(WatchState::stop);
        watches.clear();
    }

    public WorkspaceModel currentModel(Path root) {
        var state = watches.get(root);
        return state != null ? state.model : null;
    }

    public java.util.List<WorkspaceModel> allModels() {
        return watches.values().stream()
                      .map(ws -> ws.model)
                      .filter(java.util.Objects::nonNull)
                      .toList();
    }


    public void onWorkspaceChanged(@Observes @WorkspaceChanged Path root) {
        rescan(root);
    }

    private void rescan(Path root) {
        var state = watches.get(root);
        if (state == null) {return;}

        var newModel = scanner.scan(root);
        WorkspaceModel oldModel;
        synchronized (state) {
            oldModel = state.model;
            state.model = newModel;
        }

        if (!oldModel.repos().equals(newModel.repos())) {
            broadcaster.broadcast(TOPIC_REPOS, newModel.repos());
        }
        if (!oldModel.slots().equals(newModel.slots())) {
            broadcaster.broadcast(TOPIC_SLOTS, newModel.slots());
        }
        if (!oldModel.repos().equals(newModel.repos())) {
            broadcaster.broadcast(TOPIC_PROTOCOLS, "changed");
        }
    }

    private void startWatcher(WatchState state) {
        Thread.ofVirtual().name("trellis-watcher-init-" + state.root.getFileName()).start(() -> {
            if (state.stopped) return;
            try {
                var watcher = DirectoryWatcher.builder()
                                              .path(state.root)
                                              .listener(event -> rescan(state.root))
                                              .build();
                state.directoryWatcher = watcher;
                watcher.watchAsync();
                LOG.infof("Directory watcher started for %s", state.root);
            } catch (IOException e) {
                LOG.warnf(e, "Failed to start directory watcher for %s — fallback rescan will continue", state.root);
            }
        });
    }

    private void startRescanFallback(WatchState state) {
        scheduler.scheduleAtFixedRate(() -> {
            if (state.stopped) {return;}
            try {
                rescan(state.root);
            } catch (Exception e) {
                LOG.warnf(e, "Rescan failed for %s", state.root);
            }
        }, RESCAN_INTERVAL_SECONDS, RESCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private static class WatchState {
        final    Path             root;
        volatile WorkspaceModel   model;
        volatile DirectoryWatcher directoryWatcher;
        volatile boolean          stopped;

        WatchState(Path root, WorkspaceModel initialModel) {
            this.root  = root;
            this.model = initialModel;
        }

        void stop() {
            stopped = true;
            if (directoryWatcher != null) {
                try {directoryWatcher.close();} catch (IOException ignored) {}
            }
        }
    }
}
