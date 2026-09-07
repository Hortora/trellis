package io.hortora.trellis.scanner;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class FileWatcherDebouncer {

    private final ScheduledExecutorService scheduler;
    private final int idleWaitSeconds;
    private final int maxWaitSeconds;
    private final Consumer<Set<Path>> drainCallback;

    private final Object lock = new Object();
    private Set<Path> accumulated = new HashSet<>();
    private ScheduledFuture<?> idleTimer;
    private ScheduledFuture<?> maxCapTimer;

    public FileWatcherDebouncer(ScheduledExecutorService scheduler,
                                int idleWaitSeconds,
                                int maxWaitSeconds,
                                Consumer<Set<Path>> drainCallback) {
        this.scheduler = scheduler;
        this.idleWaitSeconds = idleWaitSeconds;
        this.maxWaitSeconds = maxWaitSeconds;
        this.drainCallback = drainCallback;
    }

    public void onFileChanged(Path path) {
        synchronized (lock) {
            accumulated.add(path);

            if (maxCapTimer == null) {
                maxCapTimer = scheduler.schedule(
                        this::drainFromTimer, maxWaitSeconds, TimeUnit.SECONDS);
            }

            if (idleTimer != null) {
                idleTimer.cancel(false);
            }
            idleTimer = scheduler.schedule(
                    this::drainFromTimer, idleWaitSeconds, TimeUnit.SECONDS);
        }
    }

    private void drainFromTimer() {
        Set<Path> batch;
        synchronized (lock) {
            if (accumulated.isEmpty()) return;
            batch = accumulated;
            accumulated = new HashSet<>();
            if (idleTimer != null) {
                idleTimer.cancel(false);
                idleTimer = null;
            }
            if (maxCapTimer != null) {
                maxCapTimer.cancel(false);
                maxCapTimer = null;
            }
        }
        drainCallback.accept(batch);
    }
}
