package io.hortora.trellis.coordinator;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@TestProfile(CoordinatorGracefulDegradationTest.DisabledProfile.class)
class CoordinatorGracefulDegradationTest {

    @Test
    void messageReturns503WhenDisabled() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                {"workspace": "ws", "epicRef": "ref", "message": "hello"}
                """)
            .when().post("/api/coordinator/message")
            .then().statusCode(503);
    }

    @Test
    void statusShowsDisabled() {
        given()
            .when().get("/api/coordinator/status")
            .then()
            .statusCode(200)
            .body("enabled", is(false));
    }

    @Test
    void adviceStillReturnsEmpty() {
        given()
            .queryParam("workspace", "/tmp/test")
            .when().get("/api/coordinator/advice")
            .then()
            .statusCode(200);
    }

    @Test
    void conversationStillReturnsEmpty() {
        given()
            .queryParam("workspace", "/tmp/test")
            .when().get("/api/coordinator/conversation")
            .then()
            .statusCode(200);
    }

    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("trellis.coordinator.enabled", "false");
        }
    }
}
