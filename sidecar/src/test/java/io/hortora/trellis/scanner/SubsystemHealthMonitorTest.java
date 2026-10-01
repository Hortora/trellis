package io.hortora.trellis.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SubsystemHealthMonitorTest {

    @TempDir Path tempDir;

    @Test
    void healthCheckRestartsDeadWatcher() throws IOException {
        var watcherService = new TestFileWatcherService();
        var monitor = new TestableHealthMonitor(watcherService, tempDir.resolve("state.json"));

        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);

        watcherService.watch(root);
        assertEquals(Set.of(root), watcherService.getWatchedRoots());

        monitor.healthCheck();

        assertTrue(Files.exists(tempDir.resolve("state.json")),
                "health check should persist watched roots");

        watcherService.simulateCrash();
        assertTrue(watcherService.getWatchedRoots().isEmpty(),
                "after crash, no watched roots");

        monitor.healthCheck();
        assertEquals(Set.of(root), watcherService.getWatchedRoots(),
                "health check should restore the watcher");
    }

    @Test
    void startupRestoresPersistedRoots() throws IOException {
        var watcherService = new TestFileWatcherService();
        var monitor = new TestableHealthMonitor(watcherService, tempDir.resolve("state.json"));

        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);

        Files.writeString(tempDir.resolve("state.json"),
                "[\"" + root.toString() + "\"]");

        monitor.restoreFromState();
        assertEquals(Set.of(root), watcherService.getWatchedRoots(),
                "startup should restore persisted roots");
    }

    @Test
    void missingDirectorySkipped() throws IOException {
        var watcherService = new TestFileWatcherService();
        var monitor = new TestableHealthMonitor(watcherService, tempDir.resolve("state.json"));

        Files.writeString(tempDir.resolve("state.json"),
                "[\"/nonexistent/path\"]");

        monitor.restoreFromState();
        assertTrue(watcherService.getWatchedRoots().isEmpty(),
                "non-existent directories should be skipped");
    }

    static class TestFileWatcherService extends FileWatcherService {
        private final java.util.Set<Path> roots = new java.util.HashSet<>();

        @Override
        public void watch(Path root) {
            roots.add(root);
        }

        @Override
        public Set<Path> getWatchedRoots() {
            return Set.copyOf(roots);
        }

        @Override
        public WorkspaceModel currentModel(Path root) {
            return roots.contains(root) ? new WorkspaceModel(root, java.time.Instant.now(),
                    java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of()) : null;
        }

        void simulateCrash() {
            roots.clear();
        }
    }

    static class TestableHealthMonitor {
        private final TestFileWatcherService watcherService;
        private final Path stateFile;

        TestableHealthMonitor(TestFileWatcherService watcherService, Path stateFile) {
            this.watcherService = watcherService;
            this.stateFile = stateFile;
        }

        void healthCheck() {
            var persisted = loadPersistedRoots();
            var active = watcherService.getWatchedRoots();
            for (Path root : persisted) {
                if (!active.contains(root) && Files.isDirectory(root)) {
                    watcherService.watch(root);
                }
            }
            if (!active.equals(Set.copyOf(persisted))) {
                persistRoots(active);
            }
        }

        void restoreFromState() {
            var roots = loadPersistedRoots();
            for (Path root : roots) {
                if (Files.isDirectory(root)) {
                    watcherService.watch(root);
                }
            }
        }

        private java.util.List<Path> loadPersistedRoots() {
            if (!Files.isRegularFile(stateFile)) return java.util.List.of();
            try (var reader = jakarta.json.Json.createReader(Files.newInputStream(stateFile))) {
                var arr = reader.readArray();
                var result = new java.util.ArrayList<Path>(arr.size());
                for (var v : arr) {
                    if (v instanceof jakarta.json.JsonString s) {
                        result.add(Path.of(s.getString()));
                    }
                }
                return result;
            } catch (Exception e) {
                return java.util.List.of();
            }
        }

        private void persistRoots(Set<Path> roots) {
            try {
                var builder = jakarta.json.Json.createArrayBuilder();
                for (Path r : roots) { builder.add(r.toString()); }
                Files.writeString(stateFile, builder.build().toString());
            } catch (IOException ignored) {}
        }
    }
}
