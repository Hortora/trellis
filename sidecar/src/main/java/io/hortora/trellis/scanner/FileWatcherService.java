package io.hortora.trellis.scanner;

import io.casehub.pages.push.EventBroadcaster;
import io.hortora.trellis.config.PreferencesService;
import io.methvin.watcher.DirectoryWatcher;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class FileWatcherService {

    private static final Logger LOG                     = Logger.getLogger(FileWatcherService.class);
    private static final String TOPIC_REPOS      = "workspace:repos";
    private static final String TOPIC_SLOTS      = "workspace:slots";
    private static final String TOPIC_PROTOCOLS  = "workspace:protocols";
    private static final String TOPIC_LIFECYCLE  = "workspace:lifecycle";
    private static final String TOPIC_WORKLOG    = "workspace:worklog";

    @Inject WorkspaceScanner scanner;
    @Inject EventBroadcaster broadcaster;
    @Inject PreferencesService preferences;

    private final PathDomainClassifier classifier = new PathDomainClassifier();
    private final ConcurrentHashMap<Path, WatchState> watches = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "trellis-watcher"); t.setDaemon(true); return t; });
    private DirectoryWatcher worklogWatcher;

    public void watch(Path root) {
        watches.computeIfAbsent(root, r -> {
            var state = new WatchState(r, scanner.scan(r));
            state.debouncer = new FileWatcherDebouncer(
                    scheduler,
                    preferences.watcherDebounceIdleSeconds(),
                    preferences.watcherDebounceMaxWaitSeconds(),
                    paths -> onDebouncedChange(r, paths));
            startWatcher(state);
            startRescanFallback(state);
            return state;
        });
        if (watches.size() == 1 && worklogWatcher == null) {
            watchWorklog();
        }
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
        if (worklogWatcher != null) {
            try { worklogWatcher.close(); } catch (IOException ignored) {}
        }
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
        fullRescan(root);
    }

    private void onDebouncedChange(Path root, Set<Path> changedPaths) {
        var state = watches.get(root);
        if (state == null) return;

        var domains = new HashSet<PathDomainClassifier.Domain>();
        for (Path p : changedPaths) {
            domains.addAll(classifier.classify(p, root));
        }
        if (domains.isEmpty()) return;

        WorkspaceModel oldModel;
        synchronized (state) {
            oldModel = state.model;
        }

        if (domains.contains(PathDomainClassifier.Domain.REPOS)) {
            var newRepos = scanner.scanRepos(root);
            if (!oldModel.repos().equals(newRepos)) {
                synchronized (state) { state.model = state.model.withRepos(newRepos); }
                broadcaster.broadcast(TOPIC_REPOS, newRepos);
            }
        }

        if (domains.contains(PathDomainClassifier.Domain.SLOTS)) {
            var newSlots = scanner.scanSlots(root);
            if (!oldModel.slots().equals(newSlots)) {
                synchronized (state) { state.model = state.model.withSlots(newSlots); }
                broadcaster.broadcast(TOPIC_SLOTS, newSlots);
            }
        }

        if (domains.contains(PathDomainClassifier.Domain.PROTOCOLS)) {
            broadcaster.broadcast(TOPIC_PROTOCOLS, "changed");
        }

        if (domains.contains(PathDomainClassifier.Domain.LIFECYCLE)) {
            var pauses = new ArrayList<PauseEntry>();
            var epics = new ArrayList<EpicInfo>();
            scanner.scanWorkspaces(root, pauses, epics);
            synchronized (state) {
                state.model = state.model.withPausesAndEpics(
                        List.copyOf(pauses), List.copyOf(epics));
            }
            broadcaster.broadcast(TOPIC_LIFECYCLE, "changed");
        }
    }

    private void fullRescan(Path root) {
        var state = watches.get(root);
        if (state == null) return;
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

    private void watchWorklog() {
        Path hortoraDir = Path.of(System.getProperty("user.home"), ".hortora");
        if (!Files.isDirectory(hortoraDir)) return;
        try {
            var debouncer = new FileWatcherDebouncer(
                    scheduler,
                    preferences.watcherDebounceIdleSeconds(),
                    preferences.watcherDebounceMaxWaitSeconds(),
                    paths -> {
                        boolean worklogChanged = paths.stream()
                                .anyMatch(p -> "worklog.db".equals(p.getFileName().toString()));
                        if (worklogChanged) {
                            broadcaster.broadcast(TOPIC_WORKLOG, "changed");
                        }
                    });
            worklogWatcher = DirectoryWatcher.builder()
                    .path(hortoraDir)
                    .listener(event -> debouncer.onFileChanged(event.path()))
                    .build();
            worklogWatcher.watchAsync();
            LOG.info("Worklog watcher started");
        } catch (IOException e) {
            LOG.warnf(e, "Failed to start worklog watcher");
        }
    }

    private void startWatcher(WatchState state) {
        Thread.ofVirtual().name("trellis-watcher-init-" + state.root.getFileName()).start(() -> {
            if (state.stopped) return;
            try {
                var watcher = DirectoryWatcher.builder()
                                              .path(state.root)
                                              .listener(event -> state.debouncer.onFileChanged(event.path()))
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
        int interval = preferences.watcherFallbackRescanSeconds();
        scheduler.scheduleAtFixedRate(() -> {
            if (state.stopped) {return;}
            try {
                fullRescan(state.root);
            } catch (Exception e) {
                LOG.warnf(e, "Rescan failed for %s", state.root);
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    private static class WatchState {
        final    Path             root;
        volatile WorkspaceModel   model;
        volatile DirectoryWatcher directoryWatcher;
        volatile boolean          stopped;
        FileWatcherDebouncer      debouncer;

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
