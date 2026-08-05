package io.hortora.trellis;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class HealthResourceTest {

    @Test
    void healthEndpointReturnsOk() {
        given()
            .when().get("/api/health")
            .then()
            .statusCode(200)
            .body("status", is("ok"));
    }

    @Test
    void readyEndpointReturns200AfterBootstrap() {
        given()
                .when().get("/api/health/ready")
                .then()
                .statusCode(200)
                .body("status", is("ready"));
    }

}
