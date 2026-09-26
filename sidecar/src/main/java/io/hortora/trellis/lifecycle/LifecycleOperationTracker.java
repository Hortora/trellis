package io.hortora.trellis.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.casehub.pages.push.EventBroadcaster;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@ApplicationScoped
public class LifecycleOperationTracker {

    private static final Logger LOG = Logger.getLogger(LifecycleOperationTracker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());
    private static final Duration COMPLETED_TTL = Duration.ofMinutes(5);
    private static final Duration FAILED_TTL = Duration.ofHours(1);

    private final ConcurrentHashMap<String, OperationProgress> operations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> slotToOperation = new ConcurrentHashMap<>();
    private final EventBroadcaster broadcaster;
    private final Path operationsDir;

    @Inject
    public LifecycleOperationTracker(EventBroadcaster broadcaster,
            @org.eclipse.microprofile.config.inject.ConfigProperty(
                    name = "trellis.data.dir", defaultValue = ".trellis")
            String dataDir) {
        this.broadcaster = broadcaster;
        this.operationsDir = Path.of(dataDir).resolve("operations");
    }

    LifecycleOperationTracker(EventBroadcaster broadcaster, Path operationsDir) {
        this.broadcaster = broadcaster;
        this.operationsDir = operationsDir;
    }

    public String startOperation(String type, String slotId, List<String> stepNames) {
        var existing = slotToOperation.get(slotId);
        if (existing != null) {
            var prev = operations.get(existing);
            if (prev != null && prev.state() != OperationState.RUNNING) {
                operations.remove(existing);
                slotToOperation.remove(slotId);
                deleteFile(existing);
            }
        }

        var operationId = UUID.randomUUID().toString();
        var steps = stepNames.stream()
                .map(StepProgress::new)
                .toList();
        var progress = new OperationProgress(operationId, type, slotId,
                OperationState.RUNNING, List.copyOf(steps), Instant.now(), null, null);
        operations.put(operationId, progress);
        slotToOperation.put(slotId, operationId);
        persist(progress);
        return operationId;
    }

    public void stepStarted(String operationId, String stepName) {
        operations.computeIfPresent(operationId, (id, op) -> {
            var step = findStep(op, stepName).withState(StepState.RUNNING);
            var updated = op.withStep(stepName, step);
            persist(updated);
            broadcastStep(updated, step);
            return updated;
        });
    }

    public void stepCompleted(String operationId, String stepName,
                              String stdout, String stderr) {
        operations.computeIfPresent(operationId, (id, op) -> {
            var step = findStep(op, stepName).withOutput(StepState.DONE, stdout, stderr);
            var updated = op.withStep(stepName, step);
            persist(updated);
            broadcastStep(updated, step);
            return updated;
        });
    }

    public void stepFailed(String operationId, String stepName,
                           String stdout, String stderr) {
        operations.computeIfPresent(operationId, (id, op) -> {
            var step = findStep(op, stepName).withOutput(StepState.FAILED, stdout, stderr);
            var updated = op.withStep(stepName, step).withState(OperationState.FAILED);
            persist(updated);
            broadcastStep(updated, step);
            return updated;
        });
    }

    public void operationCompleted(String operationId) {
        operations.computeIfPresent(operationId, (id, op) -> {
            var updated = op.withState(OperationState.COMPLETED);
            persist(updated);
            broadcastOperation(updated);
            return updated;
        });
    }

    public void operationFailed(String operationId, String errorMessage) {
        operations.computeIfPresent(operationId, (id, op) -> {
            var updated = op.withFailed(errorMessage);
            persist(updated);
            broadcastOperation(updated);
            return updated;
        });
    }

    public OperationProgress getProgress(String operationId) {
        return operations.get(operationId);
    }

    public OperationProgress getActiveOperation(String slotId) {
        var opId = slotToOperation.get(slotId);
        return opId != null ? operations.get(opId) : null;
    }

    @Scheduled(every = "60s")
    void evictStale() {
        var now = Instant.now();
        operations.entrySet().removeIf(e -> {
            var op = e.getValue();
            if (op.state() == OperationState.COMPLETED
                    && op.completedAt() != null
                    && Duration.between(op.completedAt(), now).compareTo(COMPLETED_TTL) > 0) {
                slotToOperation.remove(op.slotId(), e.getKey());
                deleteFile(e.getKey());
                return true;
            }
            if (op.state() == OperationState.FAILED
                    && op.completedAt() != null
                    && Duration.between(op.completedAt(), now).compareTo(FAILED_TTL) > 0) {
                slotToOperation.remove(op.slotId(), e.getKey());
                deleteFile(e.getKey());
                return true;
            }
            return false;
        });
    }

    void loadOnStartup(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event) {
        if (!Files.isDirectory(operationsDir)) return;
        try (Stream<Path> files = Files.list(operationsDir)) {
            files.filter(f -> f.toString().endsWith(".json")).forEach(f -> {
                try {
                    var op = MAPPER.readValue(f.toFile(), OperationProgress.class);
                    if (op.state() == OperationState.RUNNING) {
                        op = op.withFailed("Sidecar restarted during operation");
                    }
                    operations.put(op.operationId(), op);
                    slotToOperation.put(op.slotId(), op.operationId());
                } catch (IOException e) {
                    LOG.warnf("Failed to load operation file %s: %s", f, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOG.warnf("Failed to scan operations directory: %s", e.getMessage());
        }
    }

    private StepProgress findStep(OperationProgress op, String stepName) {
        return op.steps().stream()
                .filter(s -> s.name().equals(stepName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown step: " + stepName));
    }

    private void persist(OperationProgress op) {
        try {
            Files.createDirectories(operationsDir);
            var tmp = operationsDir.resolve(op.operationId() + ".tmp");
            var target = operationsDir.resolve(op.operationId() + ".json");
            MAPPER.writeValue(tmp.toFile(), op);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warnf("Failed to persist operation %s: %s", op.operationId(), e.getMessage());
        }
    }

    private void deleteFile(String operationId) {
        try {
            Files.deleteIfExists(operationsDir.resolve(operationId + ".json"));
        } catch (IOException e) {
            LOG.debugf("Failed to delete operation file %s: %s", operationId, e.getMessage());
        }
    }

    private void broadcastStep(OperationProgress op, StepProgress step) {
        var payload = new HashMap<String, Object>();
        payload.put("operationId", op.operationId());
        payload.put("slotId", op.slotId());
        payload.put("operationType", op.operationType());
        payload.put("step", step.name());
        payload.put("state", step.state().name());
        payload.put("stdout", step.stdout() != null ? step.stdout() : "");
        payload.put("stderr", step.stderr() != null ? step.stderr() : "");
        payload.put("operationState", op.state().name());
        broadcaster.broadcast("lifecycle:progress", payload);
    }

    private void broadcastOperation(OperationProgress op) {
        var payload = new HashMap<String, Object>();
        payload.put("operationId", op.operationId());
        payload.put("slotId", op.slotId());
        payload.put("operationType", op.operationType());
        payload.put("step", null);
        payload.put("state", null);
        payload.put("operationState", op.state().name());
        payload.put("errorMessage", op.errorMessage());
        broadcaster.broadcast("lifecycle:progress", payload);
    }
}
