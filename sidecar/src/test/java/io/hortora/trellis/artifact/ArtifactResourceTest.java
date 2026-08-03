package io.hortora.trellis.artifact;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ArtifactResourceTest {

    static Path workspace;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void setup() throws IOException {
        workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        var proj = tempDir.resolve("project");
        Files.createDirectories(proj.resolve("docs"));
        Files.createSymbolicLink(workspace.resolve("proj"), proj);
        Files.createDirectories(workspace.resolve("specs"));
        Files.writeString(workspace.resolve("specs/test-spec.md"), "# Test Spec\n\nContent here.");
        Files.writeString(proj.resolve("docs/ARC42STORIES.MD"), "# Design Doc");
    }

    @Test
    void listReturnsArtifacts() {
        given()
            .queryParam("root", workspace.toString())
        .when()
            .get("/api/artifacts")
        .then()
            .statusCode(200)
            .body("size()", greaterThan(0))
            .body("[0].type", notNullValue())
            .body("[0].name", notNullValue());
    }

    @Test
    void listReturns400WithoutRoot() {
        given()
        .when()
            .get("/api/artifacts")
        .then()
            .statusCode(400);
    }

    @Test
    void listReturns404ForMissingRoot() {
        given()
            .queryParam("root", "/nonexistent/path")
        .when()
            .get("/api/artifacts")
        .then()
            .statusCode(404);
    }

    @Test
    void contentServesMarkdown() {
        given()
            .queryParam("root", workspace.toString())
            .queryParam("path", workspace.resolve("specs/test-spec.md").toString())
        .when()
            .get("/api/artifacts/content")
        .then()
            .statusCode(200)
            .contentType("text/plain")
            .body(containsString("# Test Spec"));
    }

    @Test
    void contentReturns403ForPathTraversal() {
        given()
            .queryParam("root", workspace.toString())
            .queryParam("path", "/etc/passwd")
        .when()
            .get("/api/artifacts/content")
        .then()
            .statusCode(403);
    }

    @Test
    void contentReturns404ForMissingFile() {
        given()
            .queryParam("root", workspace.toString())
            .queryParam("path", workspace.resolve("specs/nonexistent.md").toString())
        .when()
            .get("/api/artifacts/content")
        .then()
            .statusCode(404);
    }

    @Test
    void listReturnsEmptyForEmptyWorkspace() throws IOException {
        var empty = tempDir.resolve("empty-ws");
        Files.createDirectories(empty);
        given()
            .queryParam("root", empty.toString())
        .when()
            .get("/api/artifacts")
        .then()
            .statusCode(200)
            .body("size()", is(0));
    }
}
