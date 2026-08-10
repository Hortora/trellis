package io.hortora.trellis.backlog;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.sql.Connection;
import java.sql.SQLException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
class BacklogResourceTest {

    @Inject
    WorklogDataSourceProducer producer;

    @ConfigProperty(name = "trellis.worklog.db-path",
                    defaultValue = "${user.home}/.hortora/worklog.db")
    String dbPath;

    private Connection writableConn;

    private Connection openWritable() throws SQLException {
        var resolved = dbPath.replace("${user.home}", System.getProperty("user.home"));
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + resolved);
        return ds.getConnection();
    }

    @BeforeEach
    void seedData() throws SQLException {
        assumeTrue(producer.isDbAvailable(), "worklog.db not available — skipping");
        writableConn = openWritable();
        writableConn.createStatement().execute(
            "DELETE FROM github_issue_cache WHERE issue_repo LIKE 'Test/%'");
        writableConn.createStatement().execute(
            "DELETE FROM issue_enrichment WHERE issue_repo LIKE 'Test/%'");
        writableConn.createStatement().execute(
            "DELETE FROM trajectory_notes WHERE issue_repo LIKE 'Test/%'");

        writableConn.createStatement().execute(
            "INSERT INTO github_issue_cache (issue_number, issue_repo, title, state, labels, cached_at) VALUES " +
            "(1, 'Test/repo', 'Open enriched', 'OPEN', '[\"bug\"]', '2026-08-09T10:00:00Z')," +
            "(2, 'Test/repo', 'Open unenriched', 'OPEN', '[]', '2026-08-09T10:00:00Z')," +
            "(3, 'Test/repo', 'Closed issue', 'CLOSED', '[]', '2026-08-09T10:00:00Z')," +
            "(4, 'Test/other', 'Other repo', 'OPEN', '[]', '2026-08-09T10:00:00Z')");

        writableConn.createStatement().execute(
            "INSERT INTO issue_enrichment (issue_number, issue_repo, strategic_role, readiness, decay, blast_radius, cohesion, updated_at) VALUES " +
            "(1, 'Test/repo', 'quick-win', 'ready', 'compounding', 'isolated', 'infra', '2026-08-09T10:00:00Z')");

        writableConn.createStatement().execute(
            "INSERT INTO trajectory_notes (issue_number, issue_repo, note, source_branch, created_at) VALUES " +
            "(1, 'Test/repo', 'Old note', 'branch-1', '2026-08-08T10:00:00Z')," +
            "(1, 'Test/repo', 'Latest note', 'branch-2', '2026-08-09T10:00:00Z')");
    }

    @AfterEach
    void cleanData() throws SQLException {
        if (writableConn == null) return;
        try {
            writableConn.createStatement().execute(
                "DELETE FROM github_issue_cache WHERE issue_repo LIKE 'Test/%'");
            writableConn.createStatement().execute(
                "DELETE FROM issue_enrichment WHERE issue_repo LIKE 'Test/%'");
            writableConn.createStatement().execute(
                "DELETE FROM trajectory_notes WHERE issue_repo LIKE 'Test/%'");
        } finally {
            writableConn.close();
        }
    }

    @Test
    void returnsAllOpenIssuesForRepo() {
        given().queryParam("repo", "Test/repo")
            .when().get("/api/backlog")
            .then().statusCode(200)
            .body("size()", is(2))
            .body("issueNumber", hasItems(1, 2))
            .body("issueNumber", not(hasItem(3)));
    }

    @Test
    void filtersByRepo() {
        given().queryParam("repo", "Test/other")
            .when().get("/api/backlog")
            .then().statusCode(200)
            .body("size()", is(1))
            .body("[0].issueRepo", is("Test/other"));
    }

    @Test
    void enrichedIssueHasClassifications() {
        given().queryParam("repo", "Test/repo")
            .when().get("/api/backlog")
            .then().statusCode(200)
            .body("find { it.issueNumber == 1 }.strategicRole", is("quick-win"))
            .body("find { it.issueNumber == 1 }.readiness", is("ready"))
            .body("find { it.issueNumber == 1 }.decay", is("compounding"))
            .body("find { it.issueNumber == 1 }.blastRadius", is("isolated"))
            .body("find { it.issueNumber == 1 }.cohesion", is("infra"));
    }

    @Test
    void unenrichedIssueHasNullClassifications() {
        given().queryParam("repo", "Test/repo")
            .when().get("/api/backlog")
            .then().statusCode(200)
            .body("find { it.issueNumber == 2 }.strategicRole", nullValue())
            .body("find { it.issueNumber == 2 }.readiness", nullValue());
    }

    @Test
    void trajectoryNoteIsMostRecent() {
        given().queryParam("repo", "Test/repo")
            .when().get("/api/backlog")
            .then().statusCode(200)
            .body("find { it.issueNumber == 1 }.trajectoryNote", is("Latest note"))
            .body("find { it.issueNumber == 2 }.trajectoryNote", nullValue());
    }

    @Test
    void labelsAreParsedArray() {
        given().queryParam("repo", "Test/repo")
            .when().get("/api/backlog")
            .then().statusCode(200)
            .body("find { it.issueNumber == 1 }.labels", is(java.util.List.of("bug")))
            .body("find { it.issueNumber == 2 }.labels", is(java.util.List.of()));
    }
}
