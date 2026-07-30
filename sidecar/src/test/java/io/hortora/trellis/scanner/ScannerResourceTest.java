package io.hortora.trellis.scanner;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ScannerResourceTest {

    @TempDir
    Path tempRoot;

    @Test
    void scanReturnsWorkspaceModel() throws IOException {
        Files.createDirectories(tempRoot.resolve("engine/.git/refs/heads"));
        Files.writeString(tempRoot.resolve("engine/.git/HEAD"), "ref: refs/heads/main\n");

        given()
            .queryParam("root", tempRoot.toString())
            .when().get("/api/workspace")
            .then()
            .statusCode(200)
            .body("repos.size()", is(1))
            .body("repos[0].name", is("engine"))
            .body("repos[0].branch", is("main"))
            .body("root", is(tempRoot.toString()))
            .body("scannedAt", notNullValue());
    }

    @Test
    void scanReturnsBadRequestWithoutRoot() {
        given()
            .when().get("/api/workspace")
            .then()
            .statusCode(400)
            .body("error", containsString("root"));
    }

    @Test
    void scanReturnsNotFoundForMissingRoot() {
        given()
            .queryParam("root", "/nonexistent/path/12345")
            .when().get("/api/workspace")
            .then()
            .statusCode(404)
            .body("error", containsString("not found"));
    }

    @Test
    void scanIncludesSlots() throws IOException {
        Files.createDirectories(tempRoot.resolve("worktrees/5"));
        Files.writeString(tempRoot.resolve("worktrees/5/.slot"), """
                # Slot 5
                
                ## Issue
                org/repo#42
                
                ## Repos
                - myrepo
                """);

        given()
            .queryParam("root", tempRoot.toString())
            .when().get("/api/workspace")
            .then()
            .statusCode(200)
            .body("slots.size()", is(1))
            .body("slots[0].number", is(5))
            .body("slots[0].issue", is("org/repo#42"));
    }

    @Test
    void scanIncludesPauses() throws IOException {
        Files.createDirectories(tempRoot.resolve("worktrees/1/work/design"));
        Files.writeString(tempRoot.resolve("worktrees/1/work/design/.pause-stack"), """
                - branch: issue-99-thing
                  issue: 99
                  paused: 2026-07-20T10:00:00Z
                """);

        given()
            .queryParam("root", tempRoot.toString())
            .when().get("/api/workspace")
            .then()
            .statusCode(200)
            .body("pauses.size()", is(1))
            .body("pauses[0].branch", is("issue-99-thing"))
            .body("pauses[0].issue", is(99));
    }
}
