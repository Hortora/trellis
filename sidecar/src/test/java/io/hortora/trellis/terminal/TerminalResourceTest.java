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

    @Test
    void treeEndpointReturns404ForUnknownTerminal() {
        given()
                .when()
                .get("/api/terminals/nonexistent/agent/tree")
                .then()
                .statusCode(404);
    }

    @Test
    void treeEndpointReturnsEmptyForTerminalWithNoAgent() {
        given()
                .contentType("application/json")
                .body("{\"name\":\"tree-test\",\"workingDir\":\"/tmp\"}")
                .when()
                .post("/api/terminals")
                .then()
                .statusCode(201);

        given()
                .when()
                .get("/api/terminals/tree-test/agent/tree")
                .then()
                .statusCode(200)
                .body("rootPid", equalTo(0))
                .body("totalBytes", equalTo(0))
                .body("processes.size()", equalTo(0));
    }

    @Test
    void createdTerminalAppearsInList() {
        given()
                .contentType("application/json")
                .body("{\"name\":\"list-test\",\"workingDir\":\"/tmp\",\"repo\":\"test-repo\"}")
                .when()
                .post("/api/terminals")
                .then()
                .statusCode(201);

        given()
                .when()
                .get("/api/terminals")
                .then()
                .statusCode(200)
                .body("find { it.terminalName == 'list-test' }.terminal.repo", equalTo("test-repo"));
    }

    @Test
    void createdTerminalWithAgentAppearsInList() {
        given()
                .contentType("application/json")
                .body("{\"name\":\"agent-list-test\",\"workingDir\":\"/tmp\",\"repo\":\"test-repo\",\"agent\":{}}")
                .when()
                .post("/api/terminals")
                .then()
                .statusCode(201);

        given()
                .when()
                .get("/api/terminals")
                .then()
                .statusCode(200)
                .body("find { it.terminalName == 'agent-list-test' }.terminal.repo", equalTo("test-repo"))
                .body("find { it.terminalName == 'agent-list-test' }.process.state", equalTo("STARTING"));
    }


}
