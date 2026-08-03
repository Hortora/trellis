package io.hortora.trellis.coordinator;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@ApplicationScoped
public class CountdownScheduler {

    private static final Logger LOG = Logger.getLogger(CountdownScheduler.class);

    record PendingCountdown(String actionId, Instant deadline, ScheduledFuture<?> future) {}

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "countdown-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final Map<String, PendingCountdown> countdowns = new ConcurrentHashMap<>();

    public void schedule(String actionId, int seconds, Consumer<String> onFire) {
        var deadline = Instant.now().plusSeconds(seconds);
        var future = scheduler.schedule(() -> {
            try {
                countdowns.remove(actionId);
                onFire.accept(actionId);
            } catch (Exception e) {
                LOG.warnf(e, "Countdown callback failed for %s", actionId);
            }
        }, seconds, TimeUnit.SECONDS);
        var prev = countdowns.put(actionId, new PendingCountdown(actionId, deadline, future));
        if (prev != null) prev.future().cancel(false);
    }

    public void cancel(String actionId) {
        var removed = countdowns.remove(actionId);
        if (removed != null) removed.future().cancel(false);
    }

    public void cancelAll() {
        countdowns.values().forEach(c -> c.future().cancel(false));
        countdowns.clear();
    }

    public boolean hasCountdown(String actionId) {
        return countdowns.containsKey(actionId);
    }

    public Instant deadline(String actionId) {
        var c = countdowns.get(actionId);
        return c != null ? c.deadline() : null;
    }

    public void shutdown() {
        cancelAll();
        scheduler.shutdownNow();
    }
}
