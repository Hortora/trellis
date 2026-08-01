package io.hortora.trellis.coordinator;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CoordinatorResourceTest {

    @Test
    void statusEndpointReturnsEnabled() {
        given()
            .when().get("/api/coordinator/status")
            .then()
            .statusCode(200)
            .body("enabled", is(true))
            .body("currentModel", is("claude-sonnet-5"));
    }

    @Test
    void conversationEndpointReturnsEmptyInitially() {
        given()
            .queryParam("workspace", "/tmp/test-ws-nonexistent")
            .when().get("/api/coordinator/conversation")
            .then()
            .statusCode(200)
            .body("$", hasSize(0));
    }

    @Test
    void adviceEndpointReturnsEmptyInitially() {
        given()
            .queryParam("workspace", "/tmp/test-ws-nonexistent")
            .when().get("/api/coordinator/advice")
            .then()
            .statusCode(200)
            .body("$", hasSize(0));
    }

    @Test
    void dismissNonexistentAdviceReturns204() {
        given()
            .when().post("/api/coordinator/advice/nonexistent-id/dismiss")
            .then()
            .statusCode(204);
    }
}
