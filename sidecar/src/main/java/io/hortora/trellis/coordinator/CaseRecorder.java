package io.hortora.trellis.coordinator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Statement;
import java.time.Instant;

@ApplicationScoped
public class CaseRecorder {

    private static final Logger LOG = Logger.getLogger(CaseRecorder.class);

    private final DataSource dataSource;

    @Inject
    public CaseRecorder(@CoordinatorDataSourceProducer.CoordinatorDS DataSource dataSource) {
        this.dataSource = dataSource;
    }

    static CaseRecorder forTest(DataSource ds) {
        return new CaseRecorder(ds);
    }

    public long record(CoordinatorTask task, String modelUsed) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO coordinator_cases (workspace, task_type, context_tokens, event_count, conv_depth, model_used, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, task.workspaceKey());
            ps.setString(2, task.type().name());
            ps.setInt(3, task.contextTokens());
            ps.setInt(4, task.eventCount());
            ps.setInt(5, task.conversationDepth());
            ps.setString(6, modelUsed);
            ps.setString(7, Instant.now().toString());
            ps.executeUpdate();
            var keys = ps.getGeneratedKeys();
            return keys.next() ? keys.getLong(1) : -1;
        } catch (Exception e) {
            LOG.warnf(e, "Failed to record coordinator case");
            return -1;
        }
    }

    public void recordOutcome(long caseId, String outcome) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "UPDATE coordinator_cases SET outcome = ?, outcome_at = ? WHERE id = ?")) {
            ps.setString(1, outcome);
            ps.setString(2, Instant.now().toString());
            ps.setLong(3, caseId);
            ps.executeUpdate();
        } catch (Exception e) {
            LOG.warnf(e, "Failed to record outcome for case %d", caseId);
        }
    }
}
