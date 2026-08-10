package io.hortora.trellis.backlog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Path("/api/backlog")
@Produces(MediaType.APPLICATION_JSON)
public class BacklogResource {

    @Inject
    WorklogDataSourceProducer producer;

    @Inject
    @WorklogDataSourceProducer.WorklogDS
    DataSource ds;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String QUERY = """
        SELECT c.issue_number, c.issue_repo, c.title, c.labels, c.cached_at,
               e.strategic_role, e.readiness, e.decay, e.blast_radius, e.cohesion, e.updated_at,
               t.note AS trajectory_note, t.created_at AS trajectory_at
        FROM github_issue_cache c
        LEFT JOIN issue_enrichment e
          ON c.issue_number = e.issue_number AND c.issue_repo = e.issue_repo
        LEFT JOIN trajectory_notes t
          ON t.id = (
            SELECT id FROM trajectory_notes t2
            WHERE t2.issue_number = c.issue_number AND t2.issue_repo = c.issue_repo
            ORDER BY t2.id DESC LIMIT 1
          )
        WHERE c.state = 'OPEN'
        """;

    @GET
    public List<BacklogEntry> list(@QueryParam("repo") String repo) throws SQLException {
        if (!producer.isDbAvailable()) {
            return List.of();
        }
        var sql = repo != null && !repo.isBlank()
            ? QUERY + " AND c.issue_repo = ? ORDER BY c.issue_repo, c.issue_number"
            : QUERY + " ORDER BY c.issue_repo, c.issue_number";

        var results = new ArrayList<BacklogEntry>();
        try (var conn = ds.getConnection();
             var stmt = repo != null && !repo.isBlank()
                 ? prepareWithRepo(conn, sql, repo)
                 : conn.prepareStatement(sql);
             var rs = stmt.executeQuery()) {
            while (rs.next()) {
                results.add(mapRow(rs));
            }
        }
        return results;
    }

    private java.sql.PreparedStatement prepareWithRepo(
            java.sql.Connection conn, String sql, String repo) throws SQLException {
        var stmt = conn.prepareStatement(sql);
        stmt.setString(1, repo);
        return stmt;
    }

    private BacklogEntry mapRow(ResultSet rs) throws SQLException {
        return new BacklogEntry(
            rs.getInt("issue_number"),
            rs.getString("issue_repo"),
            rs.getString("title"),
            parseLabels(rs.getString("labels")),
            rs.getString("cached_at"),
            rs.getString("strategic_role"),
            rs.getString("readiness"),
            rs.getString("decay"),
            rs.getString("blast_radius"),
            rs.getString("cohesion"),
            rs.getString("updated_at"),
            rs.getString("trajectory_note"),
            rs.getString("trajectory_at")
        );
    }

    private List<String> parseLabels(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
