package io.hortora.trellis.layout;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class WorkspaceLayoutResourceTest {

    @TempDir
    Path tempDir;

    @Test
    void loadLayoutReturnsEmptyObjectWhenNoFile() {
        given()
            .queryParam("root", tempDir.toString())
            .when().get("/api/workspace/layout")
            .then()
            .statusCode(200)
            .body(is("{}"));
    }

    @Test
    void saveAndLoadLayoutRoundTrip() {
        var layout = "{\"windows\":[{\"id\":\"shell-1\",\"isMain\":true,\"frames\":[]}]}";
        given()
            .contentType("application/json")
            .queryParam("root", tempDir.toString())
            .body(layout)
            .when().put("/api/workspace/layout")
            .then()
            .statusCode(204);

        given()
            .queryParam("root", tempDir.toString())
            .when().get("/api/workspace/layout")
            .then()
            .statusCode(200)
            .body("windows[0].id", is("shell-1"));
    }

    @Test
    void loadGroupsReturnsEmptyArrayWhenNoFile() {
        given()
            .queryParam("root", tempDir.toString())
            .when().get("/api/workspace/groups")
            .then()
            .statusCode(200)
            .body("groups", hasSize(0));
    }

    @Test
    void saveAndLoadGroupsRoundTrip() {
        var groups = "{\"groups\":[{\"id\":\"g-1\",\"name\":\"Engine\",\"tabs\":[]}]}";
        given()
            .contentType("application/json")
            .queryParam("root", tempDir.toString())
            .body(groups)
            .when().put("/api/workspace/groups")
            .then()
            .statusCode(204);

        given()
            .queryParam("root", tempDir.toString())
            .when().get("/api/workspace/groups")
            .then()
            .statusCode(200)
            .body("groups[0].name", is("Engine"));
    }

    @Test
    void layoutEndpointRejectsMissingRoot() {
        given()
            .when().get("/api/workspace/layout")
            .then()
            .statusCode(400);
    }

    @Test
    void groupsEndpointRejectsMissingRoot() {
        given()
            .when().get("/api/workspace/groups")
            .then()
            .statusCode(400);
    }
}
