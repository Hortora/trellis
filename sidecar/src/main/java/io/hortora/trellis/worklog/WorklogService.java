package io.hortora.trellis.worklog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hortora.trellis.mcp.GenerationCounter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

@ApplicationScoped
public class WorklogService {

    private static final Logger LOG = Logger.getLogger(WorklogService.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataSource dataSource;
    private final GenerationCounter generation;
    private final Path dbPath;
    private final AtomicReference<FileTime> lastMtime = new AtomicReference<>();
    volatile boolean dbAvailable;

    private volatile WorklogSummary cachedSummary;
    private volatile long cachedSummaryTime;
    private static final long SUMMARY_TTL_MS = 5000;

    @Inject
    public WorklogService(@WorklogDataSourceProducer.WorklogDS DataSource dataSource,
                          GenerationCounter generation,
                          WorklogDataSourceProducer producer) {
        this.dataSource = dataSource;
        this.generation = generation;
        this.dbPath = producer.getDbPath();
        this.dbAvailable = producer.isDbAvailable();
        checkSchemaVersion();
    }

    public WorklogService(DataSource dataSource, GenerationCounter generation, Path dbPath) {
        this.dataSource = dataSource;
        this.generation = generation;
        this.dbPath = dbPath;
        this.dbAvailable = true;
    }

    static WorklogService withSchemaCheck(DataSource ds, GenerationCounter gen, Path path) {
        var svc = new WorklogService(ds, gen, path);
        svc.checkSchemaVersion();
        return svc;
    }

    private void checkSchemaVersion() {
        if (!dbAvailable || dataSource == null) return;
        try (var conn = dataSource.getConnection()) {
            var version = conn.createStatement()
                    .executeQuery("PRAGMA user_version").getInt(1);
            if (version < 2) {
                LOG.warning("worklog.db schema version " + version + " is too old (minimum: 2) — disabling");
                dbAvailable = false;
            } else if (version > 2) {
                LOG.warning("worklog.db schema version " + version + " is newer than expected (2) — continuing with best effort");
            }
        } catch (SQLException e) {
            LOG.warning("worklog.db schema check failed: " + e.getMessage());
            dbAvailable = false;
        }
    }

    public boolean isDbAvailable() {
        return dbAvailable;
    }

    void checkFreshness() {
        if (!dbAvailable || dbPath == null) return;
        try {
            var mtime = Files.getLastModifiedTime(dbPath);
            var prev = lastMtime.getAndSet(mtime);
            if (prev != null && !prev.equals(mtime)) {
                generation.increment();
                cachedSummary = null;
            }
        } catch (IOException ignored) {}
    }

    public List<WorklogEvent> recentEvents(String since, String type, int limit) {
        if (!dbAvailable) return List.of();
        checkFreshness();
        var clauses = new ArrayList<String>();
        var params = new ArrayList<Object>();
        if (since != null && !since.isBlank()) {
            clauses.add("timestamp >= ?");
            params.add(since);
        }
        if (type != null && !type.isBlank()) {
            clauses.add("event_type = ?");
            params.add(type);
        }
        var where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        var sql = "SELECT * FROM events" + where + " ORDER BY id DESC LIMIT ?";
        params.add(limit);

        var results = new ArrayList<WorklogEvent>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapEvent(rs));
                }
            }
        } catch (SQLException e) {
            LOG.warning("worklog query failed (recentEvents): " + e.getMessage());
            return List.of();
        }
        return results;
    }

    public List<WorkItem> activeWork() {
        if (!dbAvailable) return List.of();
        checkFreshness();
        List<WorkItem> items = new ArrayList<>();
        var ids = new ArrayList<Long>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT wi.id, wi.branch, wi.state, wi.location, wi.slot_id, " +
                 "wi.created_at, r.path AS repo_path, r.github_repo " +
                 "FROM work_items wi JOIN repos r ON wi.repo_id = r.id " +
                 "WHERE wi.state != 'ended' ORDER BY wi.created_at")) {
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    var id = rs.getLong("id");
                    ids.add(id);
                    items.add(new WorkItem(id, rs.getString("branch"),
                            rs.getString("state"), rs.getString("location"),
                            rs.getObject("slot_id") != null ? rs.getLong("slot_id") : null,
                            rs.getString("created_at"), rs.getString("repo_path"),
                            rs.getString("github_repo"), List.of()));
                }
            }
            if (!ids.isEmpty()) {
                var issueMap = fetchIssuesForWorkItems(conn, ids);
                items = items.stream().map(wi ->
                        new WorkItem(wi.id(), wi.branch(), wi.state(), wi.location(),
                                wi.slotId(), wi.createdAt(), wi.repoPath(), wi.githubRepo(),
                                issueMap.getOrDefault(wi.id(), List.of()))
                ).toList();
            }
        } catch (SQLException e) {
            LOG.warning("worklog query failed (activeWork): " + e.getMessage());
            return List.of();
        }
        return items;
    }

    private LinkedHashMap<Long, List<WorkItemIssue>> fetchIssuesForWorkItems(
            Connection conn, List<Long> workItemIds) throws SQLException {
        var placeholders = String.join(",", workItemIds.stream().map(id -> "?").toList());
        var sql = "SELECT work_item_id, issue_number, issue_repo, is_primary " +
                  "FROM work_item_issues WHERE work_item_id IN (" + placeholders + ")";
        var map = new LinkedHashMap<Long, List<WorkItemIssue>>();
        try (var stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < workItemIds.size(); i++) {
                stmt.setLong(i + 1, workItemIds.get(i));
            }
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    var wiId = rs.getLong("work_item_id");
                    map.computeIfAbsent(wiId, k -> new ArrayList<>()).add(
                            new WorkItemIssue(rs.getInt("issue_number"),
                                    rs.getString("issue_repo"),
                                    rs.getInt("is_primary") == 1));
                }
            }
        }
        return map;
    }

    public List<SlotInfo> slotStatus(String familyRoot) {
        if (!dbAvailable) return List.of();
        checkFreshness();
        var results = new ArrayList<SlotInfo>();
        var sql = familyRoot != null && !familyRoot.isBlank()
                ? "SELECT * FROM slots WHERE family_root = ? ORDER BY slot_number"
                : "SELECT * FROM slots ORDER BY family_root, slot_number";
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            if (familyRoot != null && !familyRoot.isBlank()) {
                stmt.setString(1, Path.of(familyRoot).toAbsolutePath().normalize().toString());
            }
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(new SlotInfo(rs.getLong("id"), rs.getInt("slot_number"),
                            rs.getString("family_root"), rs.getString("state"),
                            rs.getString("created_at"), rs.getString("archived_at")));
                }
            }
        } catch (SQLException e) {
            LOG.warning("worklog query failed (slotStatus): " + e.getMessage());
            return List.of();
        }
        return results;
    }

    public List<WorklogEvent> workItemTimeline(String branch, String repoPath) {
        if (!dbAvailable) return List.of();
        checkFreshness();
        var resolvedPath = Path.of(repoPath).toAbsolutePath().normalize().toString();
        var results = new ArrayList<WorklogEvent>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT e.* FROM events e " +
                 "JOIN work_items wi ON e.work_item_id = wi.id " +
                 "JOIN repos r ON wi.repo_id = r.id " +
                 "WHERE wi.branch = ? AND r.path = ? ORDER BY e.id")) {
            stmt.setString(1, branch);
            stmt.setString(2, resolvedPath);
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapEvent(rs));
                }
            }
        } catch (SQLException e) {
            LOG.warning("worklog query failed (workItemTimeline): " + e.getMessage());
            return List.of();
        }
        return results;
    }

    public List<BacklogEntry> backlogEntries(String repo) {
        if (!dbAvailable) return List.of();
        checkFreshness();
        var baseSql = """
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
        var sql = repo != null && !repo.isBlank()
                ? baseSql + " AND c.issue_repo = ? ORDER BY c.issue_repo, c.issue_number"
                : baseSql + " ORDER BY c.issue_repo, c.issue_number";
        var results = new ArrayList<BacklogEntry>();
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(sql)) {
            if (repo != null && !repo.isBlank()) {
                stmt.setString(1, repo);
            }
            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    results.add(mapBacklogEntry(rs));
                }
            }
        } catch (SQLException e) {
            LOG.warning("worklog query failed (backlogEntries): " + e.getMessage());
            return List.of();
        }
        return results;
    }

    public PlanState planPosition(Path workspaceRoot) {
        if (workspaceRoot == null) return null;
        var planFile = workspaceRoot.resolve("design/.plan");
        if (!Files.exists(planFile)) return null;
        try {
            var lines = Files.readAllLines(planFile);
            String active = null;
            int done = 0;
            int total = 0;
            for (var line : lines) {
                var trimmed = line.trim();
                if (trimmed.startsWith("- [x]") || trimmed.startsWith("- [ ]")) {
                    total++;
                    if (trimmed.startsWith("- [x]")) done++;
                    if (trimmed.contains("← active")) {
                        var match = trimmed.replaceAll(".*?(#\\d+).*", "$1");
                        active = match;
                    }
                }
            }
            if (total == 0) return null;
            return new PlanState(active, done, total);
        } catch (IOException e) {
            return null;
        }
    }

    public WorklogSummary summary(Path workspaceRoot) {
        if (!dbAvailable) return new WorklogSummary(0, 0, null, null, 0);
        checkFreshness();
        long now = System.currentTimeMillis();
        if (cachedSummary != null && (now - cachedSummaryTime) < SUMMARY_TTL_MS) {
            return cachedSummary;
        }
        var active = activeWork();
        var recent = recentEvents(null, null, 1);
        var slots = slotStatus(null);
        var plan = planPosition(workspaceRoot);
        int activeSlots = (int) slots.stream().filter(s -> "active".equals(s.state())).count();
        int eventCount = countEvents();
        var result = new WorklogSummary(active.size(), eventCount,
                recent.isEmpty() ? null : recent.get(0), plan, activeSlots);
        cachedSummary = result;
        cachedSummaryTime = now;
        return result;
    }

    private int countEvents() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT COUNT(*) FROM events")) {
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    private WorklogEvent mapEvent(ResultSet rs) throws SQLException {
        return new WorklogEvent(
                rs.getLong("id"),
                rs.getString("timestamp"),
                rs.getString("event_type"),
                rs.getObject("work_item_id") != null ? rs.getLong("work_item_id") : null,
                rs.getObject("slot_id") != null ? rs.getLong("slot_id") : null,
                rs.getString("repo_path"),
                rs.getString("metadata"));
    }

    private BacklogEntry mapBacklogEntry(ResultSet rs) throws SQLException {
        return new BacklogEntry(
                rs.getInt("issue_number"), rs.getString("issue_repo"),
                rs.getString("title"), parseLabels(rs.getString("labels")),
                rs.getString("cached_at"), rs.getString("strategic_role"),
                rs.getString("readiness"), rs.getString("decay"),
                rs.getString("blast_radius"), rs.getString("cohesion"),
                rs.getString("updated_at"), rs.getString("trajectory_note"),
                rs.getString("trajectory_at"));
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
