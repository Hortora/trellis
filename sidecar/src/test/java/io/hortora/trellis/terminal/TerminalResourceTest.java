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


    @Test
    void createdTerminalWithPairedTerminalAppearsInResponse() {
        given()
                .contentType("application/json")
                .body("{\"name\":\"pair-primary\",\"workingDir\":\"/tmp\",\"repo\":\"engine\"}")
                .when()
                .post("/api/terminals")
                .then()
                .statusCode(201);

        given()
                .contentType("application/json")
                .body("{\"name\":\"pair-repl\",\"workingDir\":\"/tmp\",\"repo\":\"engine\",\"pairedTerminal\":\"pair-primary\"}")
                .when()
                .post("/api/terminals")
                .then()
                .statusCode(201)
                .body("terminal.pairedTerminal", equalTo("pair-primary"));
    }

    @Test
    void createdTerminalWithCommandDoesNotStartAgent() {
        given()
                .contentType("application/json")
                .body("{\"name\":\"cmd-test\",\"workingDir\":\"/tmp\",\"command\":\"echo hello\"}")
                .when()
                .post("/api/terminals")
                .then()
                .statusCode(201)
                .body("process", equalTo(null));
    }

    @Test
    void commandAndAgentTogetherReturns400() {
        given()
                .contentType("application/json")
                .body("{\"name\":\"conflict-test\",\"workingDir\":\"/tmp\",\"command\":\"echo hello\",\"agent\":{}}")
                .when()
                .post("/api/terminals")
                .then()
                .statusCode(400)
                .body("error", equalTo("command and agent are mutually exclusive"));
    }

    @Test
    void pairedTerminalAppearsInListResponse() {
        given()
                .contentType("application/json")
                .body("{\"name\":\"list-pair-a\",\"workingDir\":\"/tmp\",\"repo\":\"engine\"}")
                .when()
                .post("/api/terminals")
                .then()
                .statusCode(201);

        given()
                .contentType("application/json")
                .body("{\"name\":\"list-pair-b\",\"workingDir\":\"/tmp\",\"repo\":\"engine\",\"pairedTerminal\":\"list-pair-a\"}")
                .when()
                .post("/api/terminals")
                .then()
                .statusCode(201);

        given()
                .when()
                .get("/api/terminals")
                .then()
                .statusCode(200)
                .body("find { it.terminalName == 'list-pair-b' }.terminal.pairedTerminal", equalTo("list-pair-a"));
    }

}
