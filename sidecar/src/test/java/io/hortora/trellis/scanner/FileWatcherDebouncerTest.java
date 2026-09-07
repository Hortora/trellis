package io.hortora.trellis.scanner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class FileWatcherDebouncerTest {

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void singleEventDrainsAfterIdleWait() throws Exception {
        List<Set<Path>> drained = new ArrayList<>();
        var debouncer = new FileWatcherDebouncer(scheduler, 1, 10, drained::add);

        debouncer.onFileChanged(Path.of("/ws/repo/.git/HEAD"));

        Thread.sleep(1500);
        assertEquals(1, drained.size());
        assertEquals(Set.of(Path.of("/ws/repo/.git/HEAD")), drained.getFirst());
    }

    @Test
    void burstEventsCoalesceIntoSingleDrain() throws Exception {
        List<Set<Path>> drained = new ArrayList<>();
        var debouncer = new FileWatcherDebouncer(scheduler, 1, 10, drained::add);

        debouncer.onFileChanged(Path.of("/ws/a"));
        Thread.sleep(200);
        debouncer.onFileChanged(Path.of("/ws/b"));
        Thread.sleep(200);
        debouncer.onFileChanged(Path.of("/ws/c"));

        Thread.sleep(1500);
        assertEquals(1, drained.size());
        assertEquals(3, drained.getFirst().size());
    }

    @Test
    void maxCapForcesEarlyDrain() throws Exception {
        List<Set<Path>> drained = new ArrayList<>();
        var debouncer = new FileWatcherDebouncer(scheduler, 2, 1, drained::add);

        debouncer.onFileChanged(Path.of("/ws/first"));
        Thread.sleep(1200);

        assertFalse(drained.isEmpty(), "Max cap should have forced drain before idle wait");
    }

    @Test
    void noDrainWhenNoEvents() throws Exception {
        List<Set<Path>> drained = new ArrayList<>();
        new FileWatcherDebouncer(scheduler, 1, 10, drained::add);

        Thread.sleep(1500);
        assertTrue(drained.isEmpty());
    }

    @Test
    void resetsAfterDrain() throws Exception {
        List<Set<Path>> drained = new ArrayList<>();
        var debouncer = new FileWatcherDebouncer(scheduler, 1, 10, drained::add);

        debouncer.onFileChanged(Path.of("/ws/first"));
        Thread.sleep(1500);
        assertEquals(1, drained.size());

        debouncer.onFileChanged(Path.of("/ws/second"));
        Thread.sleep(1500);
        assertEquals(2, drained.size());
    }
}
