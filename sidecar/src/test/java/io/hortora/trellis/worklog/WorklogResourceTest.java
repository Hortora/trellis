package io.hortora.trellis.worklog;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
class WorklogResourceTest {

    @Inject
    WorklogDataSourceProducer producer;

    private Connection writableConn;

    @BeforeEach
    void seedData() throws SQLException {
        assumeTrue(producer.isDbAvailable(), "worklog.db not available — skipping");
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + producer.getDbPath());
        writableConn = ds.getConnection();

        writableConn.createStatement().execute(
            "INSERT OR IGNORE INTO repos (id, path, github_repo) VALUES " +
            "(9990, '/test/worklog-rest', 'Test/worklog-rest')");
        writableConn.createStatement().execute(
            "INSERT OR IGNORE INTO work_items (id, branch, repo_id, state, location, created_at) VALUES " +
            "(9990, 'test-rest-branch', 9990, 'active', 'primary', '2026-08-11T10:00:00Z')");
        writableConn.createStatement().execute(
            "INSERT OR IGNORE INTO work_item_issues VALUES (9990, 99, 'Test/rest', 1)");
        writableConn.createStatement().execute(
            "INSERT INTO events (timestamp, event_type, work_item_id, repo_path) VALUES " +
            "('2026-08-11T10:00:00Z', 'work-start', 9990, '/test/worklog-rest')");
        writableConn.createStatement().execute(
            "INSERT OR IGNORE INTO slots (id, slot_number, family_root, state, created_at) VALUES " +
            "(9990, 999, '/test/family', 'active', '2026-08-11T09:00:00Z')");
    }

    @AfterEach
    void cleanData() throws SQLException {
        if (writableConn == null) return;
        try {
            writableConn.createStatement().execute(
                "DELETE FROM events WHERE repo_path = '/test/worklog-rest'");
            writableConn.createStatement().execute(
                "DELETE FROM work_item_issues WHERE work_item_id = 9990");
            writableConn.createStatement().execute(
                "DELETE FROM work_items WHERE id = 9990");
            writableConn.createStatement().execute(
                "DELETE FROM repos WHERE id = 9990");
            writableConn.createStatement().execute(
                "DELETE FROM slots WHERE id = 9990");
        } finally {
            writableConn.close();
        }
    }

    @Test
    void eventsEndpointReturnsEvents() {
        given()
            .when().get("/api/worklog/events")
            .then().statusCode(200)
            .body("size()", greaterThan(0))
            .body("[0].eventType", notNullValue());
    }

    @Test
    void eventsFiltersByType() {
        given().queryParam("type", "work-start")
            .when().get("/api/worklog/events")
            .then().statusCode(200)
            .body("eventType", everyItem(is("work-start")));
    }

    @Test
    void eventsRespectsLimit() {
        given().queryParam("limit", 1)
            .when().get("/api/worklog/events")
            .then().statusCode(200)
            .body("size()", is(1));
    }

    @Test
    void workItemsReturnsActiveOnly() {
        given()
            .when().get("/api/worklog/work-items")
            .then().statusCode(200)
            .body("state", everyItem(not(is("ended"))));
    }

    @Test
    void slotsEndpointReturnsSlots() {
        given()
            .when().get("/api/worklog/slots")
            .then().statusCode(200)
            .body("size()", greaterThan(0));
    }

    @Test
    void timelineRequiresRepoPath() {
        given()
            .when().get("/api/worklog/work-items/test-rest-branch/timeline")
            .then().statusCode(400);
    }

    @Test
    void timelineReturnsEventsForBranch() {
        given().queryParam("repoPath", "/test/worklog-rest")
            .when().get("/api/worklog/work-items/test-rest-branch/timeline")
            .then().statusCode(200)
            .body("size()", greaterThan(0))
            .body("[0].eventType", is("work-start"));
    }
}
