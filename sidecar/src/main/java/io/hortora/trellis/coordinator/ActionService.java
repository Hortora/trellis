package io.hortora.trellis.coordinator;

import io.casehub.pages.push.EventBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@ApplicationScoped
public class ActionService {

    private static final Logger LOG = Logger.getLogger(ActionService.class);

    @Inject @CoordinatorDataSourceProducer.CoordinatorDS DataSource dataSource;
    @Inject EventBroadcaster broadcaster;
    @Inject Instance<ActionExecutor> executorInstances;
    @Inject
            AutonomyResolver         autonomyResolver;
    @Inject
            CountdownScheduler       countdownScheduler;
    @Inject
            io.hortora.trellis.config.PreferencesService preferences;


    private List<ActionExecutor> executors;
    private java.util.concurrent.Executor actionExecutorThread =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                var t = new Thread(r, "action-executor");
                t.setDaemon(true);
                return t;
            });


    ActionService() {}

    static ActionService forTest(DataSource ds, List<ActionExecutor> executors) {
        return forTest(ds, executors, null, null, null);
    }

    static ActionService forTest(DataSource ds, List<ActionExecutor> executors,
                                 AutonomyResolver autonomyResolver,
                                 CountdownScheduler countdownScheduler,
                                 io.hortora.trellis.config.PreferencesService preferences) {
        var s = new ActionService();
        s.dataSource           = ds;
        s.executors            = executors;
        s.autonomyResolver     = autonomyResolver;
        s.countdownScheduler   = countdownScheduler;
        s.preferences          = preferences;
        s.actionExecutorThread = Runnable::run;
        return s;
    }

    private List<ActionExecutor> executors() {
        if (executors != null) return executors;
        executors = new ArrayList<>();
        executorInstances.forEach(executors::add);
        return executors;
    }

    public ProposedAction propose(String adviceId, ActionCategory category, String actionType,
                                   Map<String, String> params, String rationale, String workspace) {
        var id   = UUID.randomUUID().toString();
        var risk = RiskClassification.riskFor(actionType);
        var now  = Instant.now();
        var action = new ProposedAction(id, category, actionType, params, risk, rationale,
                                        ActionStatus.PROPOSED, adviceId, workspace, now, null, null, null);
        persist(action);
        broadcast(action);
        applyAutonomy(action);
        return action;}

    public ProposedAction propose(Connection conn, String adviceId, ActionCategory category,
                                   String actionType, Map<String, String> params,
                                   String rationale, String workspace) {
        var id   = UUID.randomUUID().toString();
        var risk = RiskClassification.riskFor(actionType);
        var now  = Instant.now();
        var action = new ProposedAction(id, category, actionType, params, risk, rationale,
                                        ActionStatus.PROPOSED, adviceId, workspace, now, null, null, null);
        persistWithConnection(conn, action);
        broadcast(action);
        applyAutonomy(action);
        return action;}

    public ProposedAction approve(String actionId) {
        var action = getAction(actionId);
        if (action == null) {return null;}
        resetRateLimit();

        if (action.risk() == RiskLevel.HIGH) {
            int updated = updateStatusCas(actionId, ActionStatus.PROPOSED, ActionStatus.CONFIRMING);
            if (updated == 0) {return getAction(actionId);}
            var confirming = getAction(actionId);
            broadcast(confirming);
            return confirming;
        }
        int updated = updateStatusCas(actionId, ActionStatus.PROPOSED, ActionStatus.APPROVED);
        if (updated == 0) {return getAction(actionId);}
        var approved = getAction(actionId);
        broadcast(approved);
        return executeAction(approved);
    }

    public ProposedAction confirm(String actionId) {
        var action = getAction(actionId);
        if (action == null) {return null;}
        int updated = updateStatusCas(actionId, ActionStatus.CONFIRMING, ActionStatus.APPROVED);
        if (updated == 0) {return getAction(actionId);}
        var approved = getAction(actionId);
        broadcast(approved);
        return executeAction(approved);
    }

    public ProposedAction cancel(String actionId) {
        int updated = updateStatusCas(actionId, ActionStatus.CONFIRMING, ActionStatus.PROPOSED);
        if (updated == 0) {
            var current = getAction(actionId);
            if (current != null && current.status() != ActionStatus.CONFIRMING) {
                throw new IllegalStateException("Cannot cancel from " + current.status());
            }
            return current;
        }
        var proposed = getAction(actionId);
        broadcast(proposed);
        return proposed;
    }

    public ProposedAction reject(String actionId) {
        int updated = updateStatusCas(actionId, ActionStatus.PROPOSED, ActionStatus.REJECTED);
        if (updated == 0) {return getAction(actionId);}
        var rejected = getAction(actionId);
        broadcast(rejected);
        return rejected;
    }

    public void expireStale(String actionType, Map<String, String> params) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT id, category, action_type, params, risk, rationale, status, " +
                     "advice_id, workspace, proposed_at, resolved_at, execution_result, countdown_ends_at " +
                     "FROM coordinator_actions WHERE action_type = ? AND status IN ('PROPOSED', 'APPROVED', 'CONFIRMING')")) {
            ps.setString(1, actionType);
            var rs = ps.executeQuery();
            while (rs.next()) {
                var action = readAction(rs);
                if (paramsOverlap(action.params(), params)) {
                    transition(action, ActionStatus.EXPIRED, null);
                }
            }
        } catch (SQLException e) {
            LOG.warnf(e, "Failed to expire stale actions for %s", actionType);
        }
    }

    public List<ProposedAction> pendingActions(String workspace) {
        return queryActions(workspace, "status IN ('PROPOSED', 'CONFIRMING')", 50);
    }

    public List<ProposedAction> actionHistory(String workspace, int limit) {
        return queryActions(workspace, "1=1", limit);
    }

    public ProposedAction getAction(String actionId) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT id, category, action_type, params, risk, rationale, status, " +
                     "advice_id, workspace, proposed_at, resolved_at, execution_result, countdown_ends_at " +
                     "FROM coordinator_actions WHERE id = ?")) {
            ps.setString(1, actionId);
            var rs = ps.executeQuery();
            return rs.next() ? readAction(rs) : null;
        } catch (SQLException e) {
            LOG.warnf(e, "Failed to get action %s", actionId);
            return null;
        }
    }

    private ProposedAction executeAction(ProposedAction action) {
        var executor = findExecutor(action.category());
        if (executor == null) {
            return transition(action, ActionStatus.FAILED, "No executor for category: " + action.category());
        }
        var result = executor.execute(action);
        var status = result.success() ? ActionStatus.COMPLETED : ActionStatus.FAILED;
        return transition(action, status, result.detail());
    }

    private final java.util.Deque<Instant> autonomousTimestamps = new java.util.concurrent.ConcurrentLinkedDeque<>();

    void autoExecute(String actionId) {
        int updated = updateStatusCas(actionId, ActionStatus.PROPOSED, ActionStatus.APPROVED);
        if (updated == 0) {return;}
        var action = getAction(actionId);
        if (action == null) {return;}
        var approved = new ProposedAction(action.id(), action.category(),
                                          action.actionType(), action.params(), action.risk(), action.rationale(),
                                          ActionStatus.APPROVED, action.adviceId(), action.workspace(),
                                          action.proposedAt(), null, null, action.countdownEndsAt());
        broadcast(approved);
        actionExecutorThread.execute(() -> {
            try {
                var result = executeAction(approved);
                if (result != null) {notifyAutoCompletion(result);}
            } catch (Exception e) {
                LOG.warnf(e, "Auto-execution failed for %s", actionId);
            }
        });
    }

    private void notifyAutoCompletion(ProposedAction action) {
        if (broadcaster == null) {return;}
        var severity = action.status() == ActionStatus.COMPLETED ? "info" : "warning";
        var title = action.status() == ActionStatus.COMPLETED
                    ? action.actionType() + " completed"
                    : action.actionType() + " failed";
        try {
            broadcaster.broadcast("coordinator:notification", Map.of(
                    "actionId", action.id(),
                    "actionType", action.actionType(),
                    "title", title,
                    "severity", severity,
                    "detail", action.executionResult() != null ? action.executionResult() : ""
                                                                    ));
        } catch (Exception e) {
            LOG.debugf(e, "Failed to broadcast notification for %s", action.id());
        }
    }


    private void applyAutonomy(ProposedAction action) {
        if (autonomyResolver == null) {return;}
        var level = autonomyResolver.resolveLevel(action.workspace());
        if (level == AutonomyLevel.MANUAL) {return;}

        var policy = autonomyResolver.resolvePolicy(action.actionType());
        if (level == AutonomyLevel.AUTONOMOUS && policy == AutonomyOverride.AUTONOMOUS) {
            if (isWithinRateLimit()) {
                recordAutonomousExecution();
                autoExecute(action.id());
            } else {
                scheduleCountdown(action);
            }
        } else {
            scheduleCountdown(action);
        }
    }

    private void scheduleCountdown(ProposedAction action) {
        int seconds  = preferences != null ? preferences.observationCountdownSeconds() : 30;
        var deadline = Instant.now().plusSeconds(seconds);
        persistCountdownDeadline(action.id(), deadline);
        countdownScheduler.schedule(action.id(), seconds, this::autoExecute);
        var withDeadline = new ProposedAction(action.id(), action.category(),
                                              action.actionType(), action.params(), action.risk(), action.rationale(),
                                              action.status(), action.adviceId(), action.workspace(),
                                              action.proposedAt(), action.resolvedAt(), action.executionResult(), deadline);
        broadcast(withDeadline);
    }

    private boolean isWithinRateLimit() {
        pruneOldTimestamps();
        int limit = preferences != null ? preferences.rateLimitMaxActions() : 5;
        return autonomousTimestamps.size() < limit;
    }

    private void recordAutonomousExecution() {
        autonomousTimestamps.addLast(Instant.now());
    }

    private void pruneOldTimestamps() {
        int windowSeconds = preferences != null ? preferences.rateLimitWindowSeconds() : 60;
        var cutoff        = Instant.now().minusSeconds(windowSeconds);
        while (!autonomousTimestamps.isEmpty() && autonomousTimestamps.peekFirst().isBefore(cutoff)) {
            autonomousTimestamps.pollFirst();
        }
    }

    public void resetRateLimit() {
        autonomousTimestamps.clear();
    }

    private void persistCountdownDeadline(String actionId, Instant deadline) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE coordinator_actions SET countdown_ends_at = ? WHERE id = ?")) {
            ps.setString(1, deadline.toString());
            ps.setString(2, actionId);
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            LOG.warnf(e, "Failed to persist countdown deadline for %s", actionId);
        }
    }

    public void cancelAllCountdowns() {
        if (countdownScheduler != null) {countdownScheduler.cancelAll();}
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE coordinator_actions SET countdown_ends_at = NULL " +
                     "WHERE status = 'PROPOSED' AND countdown_ends_at IS NOT NULL")) {
            ps.executeUpdate();
        } catch (java.sql.SQLException e) {
            LOG.warnf(e, "Failed to clear countdown deadlines");
        }
    }

    public void recoverCountdowns(String workspace) {
        var actions = queryActions(workspace, "status = 'PROPOSED' AND countdown_ends_at IS NOT NULL", 100);
        var now     = Instant.now();
        for (var action : actions) {
            if (action.countdownEndsAt() == null) {continue;}
            if (action.countdownEndsAt().isBefore(now)) {
                autoExecute(action.id());
            } else {
                int remaining = (int) java.time.Duration.between(now, action.countdownEndsAt()).getSeconds();
                countdownScheduler.schedule(action.id(), Math.max(1, remaining), this::autoExecute);
            }
        }
    }


    private ActionExecutor findExecutor(ActionCategory category) {
        return executors().stream()
                .filter(e -> e.category() == category)
                .findFirst().orElse(null);
    }

    private void validateTransition(ActionStatus current, ActionStatus target) {
        switch (current) {
            case PROPOSED -> {
                if (target != ActionStatus.APPROVED && target != ActionStatus.CONFIRMING
                    && target != ActionStatus.REJECTED && target != ActionStatus.EXPIRED)
                    throw new IllegalStateException("Cannot transition from PROPOSED to " + target);
            }
            case CONFIRMING -> {
                if (target != ActionStatus.EXECUTING && target != ActionStatus.PROPOSED)
                    throw new IllegalStateException("Cannot transition from CONFIRMING to " + target);
            }
            case APPROVED -> {
                if (target != ActionStatus.EXECUTING)
                    throw new IllegalStateException("Cannot transition from APPROVED to " + target);
            }
            case EXECUTING -> {
                if (target != ActionStatus.COMPLETED && target != ActionStatus.FAILED)
                    throw new IllegalStateException("Cannot transition from EXECUTING to " + target);
            }
            default -> throw new IllegalStateException("Cannot transition from terminal state " + current);
        }
    }

    private ProposedAction transition(ProposedAction action, ActionStatus newStatus, String executionResult) {
        var resolvedAt = newStatus.isTerminal() ? Instant.now() : null;
        var updated = new ProposedAction(action.id(), action.category(), action.actionType(),
                action.params(), action.risk(), action.rationale(), newStatus, action.adviceId(),
                action.workspace(), action.proposedAt(), resolvedAt,
                executionResult != null ? executionResult : action.executionResult(),
                action.countdownEndsAt());
        updateStatus(updated);
        broadcast(updated);
        return updated;
    }

    private void persist(ProposedAction action) {
        try (var conn = dataSource.getConnection()) {
            persistWithConnection(conn, action);
        } catch (SQLException e) {
            LOG.warnf(e, "Failed to persist action %s", action.id());
        }
    }

    private void persistWithConnection(Connection conn, ProposedAction action) {
        try (var ps = conn.prepareStatement(
                "INSERT INTO coordinator_actions (id, advice_id, category, action_type, params, risk, " +
                "rationale, status, workspace, proposed_at, resolved_at, execution_result, countdown_ends_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, action.id());
            ps.setString(2, action.adviceId());
            ps.setString(3, action.category().name());
            ps.setString(4, action.actionType());
            ps.setString(5, serializeParams(action.params()));
            ps.setString(6, action.risk().name());
            ps.setString(7, action.rationale());
            ps.setString(8, action.status().name());
            ps.setString(9, action.workspace());
            ps.setString(10, action.proposedAt().toString());
            ps.setString(11, action.resolvedAt() != null ? action.resolvedAt().toString() : null);
            ps.setString(12, action.executionResult());
            ps.setString(13, action.countdownEndsAt() != null ? action.countdownEndsAt().toString() : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to persist action " + action.id(), e);
        }}

    private void updateStatus(ProposedAction action) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE coordinator_actions SET status = ?, resolved_at = ?, execution_result = ? WHERE id = ?")) {
            ps.setString(1, action.status().name());
            ps.setString(2, action.resolvedAt() != null ? action.resolvedAt().toString() : null);
            ps.setString(3, action.executionResult());
            ps.setString(4, action.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf(e, "Failed to update action %s", action.id());
        }
    }

    int updateStatusCas(String actionId, ActionStatus expected, ActionStatus target) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE coordinator_actions SET status = ?, resolved_at = ? " +
                     "WHERE id = ? AND status = ?")) {
            ps.setString(1, target.name());
            ps.setString(2, target.isTerminal() ? Instant.now().toString() : null);
            ps.setString(3, actionId);
            ps.setString(4, expected.name());
            return ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf(e, "CAS update failed for %s", actionId);
            return 0;
        }
    }


    private void broadcast(ProposedAction action) {
        if (broadcaster != null) {
            try {
                broadcaster.broadcast("coordinator:action", action);
            } catch (Exception e) {
                LOG.debugf(e, "Failed to broadcast action %s", action.id());
            }
        }
    }

    private List<ProposedAction> queryActions(String workspace, String statusFilter, int limit) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT id, category, action_type, params, risk, rationale, status, " +
                     "advice_id, workspace, proposed_at, resolved_at, execution_result, countdown_ends_at " +
                     "FROM coordinator_actions WHERE workspace = ? AND " + statusFilter +
                     " ORDER BY proposed_at DESC LIMIT ?")) {
            ps.setString(1, workspace);
            ps.setInt(2, limit);
            var rs = ps.executeQuery();
            var result = new ArrayList<ProposedAction>();
            while (rs.next()) {
                result.add(readAction(rs));
            }
            return result;
        } catch (SQLException e) {
            LOG.warnf(e, "Failed to query actions for %s", workspace);
            return List.of();
        }
    }

    private ProposedAction readAction(java.sql.ResultSet rs) throws SQLException {
        return new ProposedAction(
                rs.getString("id"),
                ActionCategory.valueOf(rs.getString("category")),
                rs.getString("action_type"),
                deserializeParams(rs.getString("params")),
                RiskLevel.valueOf(rs.getString("risk")),
                rs.getString("rationale"),
                ActionStatus.valueOf(rs.getString("status")),
                rs.getString("advice_id"),
                rs.getString("workspace"),
                Instant.parse(rs.getString("proposed_at")),
                rs.getString("resolved_at") != null ? Instant.parse(rs.getString("resolved_at")) : null,
                rs.getString("execution_result"),
                rs.getString("countdown_ends_at") != null ? Instant.parse(rs.getString("countdown_ends_at")) : null);
    }

    private boolean paramsOverlap(Map<String, String> actionParams, Map<String, String> targetParams) {
        for (var entry : targetParams.entrySet()) {
            if (!entry.getValue().equals(actionParams.get(entry.getKey()))) return false;
        }
        return true;
    }

    private String serializeParams(Map<String, String> params) {
        var builder = jakarta.json.Json.createObjectBuilder();
        params.forEach(builder::add);
        return builder.build().toString();
    }

    private Map<String, String> deserializeParams(String json) {
        if (json == null || json.equals("{}")) return new HashMap<>();
        try (var reader = jakarta.json.Json.createReader(new java.io.StringReader(json))) {
            var obj = reader.readObject();
            var result = new HashMap<String, String>();
            for (var key : obj.keySet()) {
                result.put(key, obj.getString(key));
            }
            return result;
        }
    }
}
