package io.hortora.trellis.terminal;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class TerminalResourceTest {

    @Test
    void startAgentWithResumeAndPromptReturns400WithMessage() {
        given()
            .contentType("application/json")
            .body("{\"name\":\"tmp-test\",\"workingDir\":\"/tmp\"}")
        .when()
            .post("/api/terminals")
        .then()
            .statusCode(201);

        given()
            .contentType("application/json")
            .body("{\"resume\":true,\"prompt\":\"hello\"}")
        .when()
            .post("/api/terminals/tmp-test/agent/start")
        .then()
            .statusCode(400)
            .body("error", equalTo("resume and prompt are mutually exclusive"));
    }
}
