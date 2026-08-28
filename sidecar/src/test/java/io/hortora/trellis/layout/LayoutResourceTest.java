package io.hortora.trellis.layout;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class LayoutResourceTest {

    @TempDir
    Path tempDir;

    @Test
    void getReturns404WhenNotFound() {
        given()
            .queryParam("root", tempDir.toString())
            .when().get("/api/layouts/workbench")
            .then()
            .statusCode(404);
    }

    @Test
    void putThenGetRoundTrips() {
        var body = "{\"docks\":{\"workspace\":true}}";
        given()
            .queryParam("root", tempDir.toString())
            .contentType("application/json")
            .body(body)
            .when().put("/api/layouts/workbench")
            .then()
            .statusCode(204);

        given()
            .queryParam("root", tempDir.toString())
            .when().get("/api/layouts/workbench")
            .then()
            .statusCode(200)
            .body(is(body));
    }

    @Test
    void deleteRemovesLayout() {
        var body = "{\"docks\":{}}";
        given()
            .queryParam("root", tempDir.toString())
            .contentType("application/json")
            .body(body)
            .when().put("/api/layouts/workbench")
            .then()
            .statusCode(204);

        given()
            .queryParam("root", tempDir.toString())
            .when().delete("/api/layouts/workbench")
            .then()
            .statusCode(204);

        given()
            .queryParam("root", tempDir.toString())
            .when().get("/api/layouts/workbench")
            .then()
            .statusCode(404);
    }

    @Test
    void multipleKeysAreIndependent() {
        var body1 = "{\"key\":\"workbench\"}";
        var body2 = "{\"key\":\"sidebar\"}";

        given().queryParam("root", tempDir.toString())
            .contentType("application/json").body(body1)
            .when().put("/api/layouts/workbench").then().statusCode(204);

        given().queryParam("root", tempDir.toString())
            .contentType("application/json").body(body2)
            .when().put("/api/layouts/sidebar").then().statusCode(204);

        given().queryParam("root", tempDir.toString())
            .when().get("/api/layouts/workbench")
            .then().statusCode(200).body(is(body1));

        given().queryParam("root", tempDir.toString())
            .when().get("/api/layouts/sidebar")
            .then().statusCode(200).body(is(body2));
    }

    @Test
    void missingRootReturns400() {
        given()
            .when().get("/api/layouts/workbench")
            .then()
            .statusCode(400);
    }
}
