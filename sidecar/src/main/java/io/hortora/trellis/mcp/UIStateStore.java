package io.hortora.trellis.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class UIStateStore {

    static final int MAX_CONTENT_SIZE = 65536;
    private static final long STALENESS_THRESHOLD_MS = 30_000;

    private final AtomicReference<Map<String, Object>> state = new AtomicReference<>();
    private volatile long lastPushTimestamp = 0;
    private final ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> pendingNavigations = new ConcurrentHashMap<>();

    @Inject
    GenerationCounter generation;

    public void update(Map<String, Object> newState) {
        state.set(newState);
        lastPushTimestamp = Instant.now().toEpochMilli();
        generation.increment();
    }

    public Map<String, Object> current() {
        return state.get();
    }

    public void clear() {
        state.set(null);
        lastPushTimestamp = 0;
    }

    public boolean hasFrontend() {
        return lastPushTimestamp > 0
                && (Instant.now().toEpochMilli() - lastPushTimestamp) < STALENESS_THRESHOLD_MS;
    }

    public CompletableFuture<Map<String, Object>> registerNavigation(String correlationId) {
        var future = new CompletableFuture<Map<String, Object>>();
        pendingNavigations.put(correlationId, future);
        return future;
    }

    @SuppressWarnings("unchecked")
    public void acknowledgeNavigation(String correlationId, Map<String, Object> postState) {
        var future = pendingNavigations.remove(correlationId);
        if (future != null) {
            future.complete(postState);
        }
    }

    public void cleanupNavigation(String correlationId) {
        pendingNavigations.remove(correlationId);
    }
}
