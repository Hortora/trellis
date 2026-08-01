package io.hortora.trellis.coordinator;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@ApplicationScoped
public class ConversationStore {

    private final DataSource dataSource;

    @Inject
    public ConversationStore(@CoordinatorDataSourceProducer.CoordinatorDS DataSource dataSource) {
        this.dataSource = dataSource;
    }

    static ConversationStore forTest(DataSource ds) {
        return new ConversationStore(ds);
    }

    public void append(String workspace, ConversationTurn.Role role, String content) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO coordinator_conversations (workspace, role, content, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, workspace);
            ps.setString(2, role.name());
            ps.setString(3, content);
            ps.setString(4, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to append conversation turn", e);
        }
    }

    public List<ConversationTurn> history(String workspace, int maxTurns) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT id, workspace, role, content, created_at FROM coordinator_conversations " +
                     "WHERE workspace = ? ORDER BY id DESC LIMIT ?")) {
            ps.setString(1, workspace);
            ps.setInt(2, maxTurns);
            var rs    = ps.executeQuery();
            var turns = new ArrayList<ConversationTurn>();
            while (rs.next()) {
                turns.add(new ConversationTurn(
                        rs.getLong("id"),
                        rs.getString("workspace"),
                        ConversationTurn.Role.valueOf(rs.getString("role")),
                        rs.getString("content"),
                        Instant.parse(rs.getString("created_at"))
                ));
            }
            Collections.reverse(turns);
            return turns;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read conversation history", e);
        }
    }
}
