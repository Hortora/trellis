package io.hortora.trellis.garden;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class GardenResourceTest {

    @InjectMock
    @RestClient
    GardenClient gardenClient;

    @Test
    void searchProxiesToEngine() {
        var response = new AdaptiveSearchResponse(
                List.of(new GardenSearchResult("jvm/GE-0031.md", "Test title",
                        "jvm", "gotcha", 8, "body", 0.9, 3.5, "garden", "GE")),
                16, 1, false, false, 0, true);
        when(gardenClient.search(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(response);

        given().queryParam("q", "test query")
                .when().get("/api/garden/search")
                .then().statusCode(200)
                .body("results.size()", equalTo(1))
                .body("results[0].title", equalTo("Test title"))
                .body("collectionReady", equalTo(true));
    }

    @Test
    void searchReturnsUnavailableOnException() {
        when(gardenClient.search(anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("connection refused"));

        given().queryParam("q", "test")
                .when().get("/api/garden/search")
                .then().statusCode(200)
                .body("available", equalTo(false));
    }

    @Test
    void entryLookupProxiesToEngine() {
        var entry = new EntryDetail("GE-0031", "Test title", "jvm", "gotcha",
                8, "body content", "garden", "GE", List.of());
        when(gardenClient.getEntry("GE-0031")).thenReturn(entry);

        given().queryParam("id", "GE-0031")
                .when().get("/api/garden/entries")
                .then().statusCode(200)
                .body("id", equalTo("GE-0031"))
                .body("title", equalTo("Test title"));
    }

    @Test
    void entryLookupNormalizesRawId() {
        var entry = new EntryDetail("GE-0031", "Test", "jvm", "gotcha",
                8, "body", "garden", "GE", List.of());
        when(gardenClient.getEntry("GE-0031")).thenReturn(entry);

        given().queryParam("id", "jvm/GE-0031.md")
                .when().get("/api/garden/entries")
                .then().statusCode(200)
                .body("id", equalTo("GE-0031"));
    }

    @Test
    void entryLookupReturns404OnEngineException() {
        when(gardenClient.getEntry(anyString()))
                .thenThrow(new WebApplicationException(404));

        given().queryParam("id", "GE-NONEXISTENT")
                .when().get("/api/garden/entries")
                .then().statusCode(404);
    }

    @Test
    void entryLookupMissingIdReturns400() {
        given().when().get("/api/garden/entries")
                .then().statusCode(400);
    }

    @Test
    void forwardLineageReturnsEnrichedRecords() {
        var records = List.of(new ProvenanceRecord("Hortora/trellis", 14,
                "spec.md", "GE-0031", "2026-08-02T12:00:00Z", "brainstorming"));
        when(gardenClient.forwardLineage("Hortora/trellis", 14)).thenReturn(records);

        given().queryParam("issueRepo", "Hortora/trellis")
                .queryParam("issueNumber", 14)
                .when().get("/api/garden/provenance")
                .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].geId", equalTo("GE-0031"))
                .body("[0].issueRepo", equalTo("Hortora/trellis"));
    }

    @Test
    void reverseLineageReturnsEnrichedRecords() {
        var records = List.of(new ProvenanceRecord("Hortora/trellis", 14,
                "", "GE-0031", "2026-08-02T12:00:00Z", "work-start"));
        when(gardenClient.reverseLineage("GE-0031")).thenReturn(records);

        given().queryParam("geId", "GE-0031")
                .when().get("/api/garden/provenance/reverse")
                .then().statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].issueNumber", equalTo(14));
    }

    @Test
    void statsProxiesToEngine() {
        var stats = new ProvenanceStats(5, 3, 2,
                List.of(new EntryRefCount("GE-0031", 3)), 0);
        when(gardenClient.stats()).thenReturn(stats);

        given().when().get("/api/garden/stats")
                .then().statusCode(200)
                .body("totalRecords", equalTo(5))
                .body("uniqueEntries", equalTo(3))
                .body("topReferenced[0].geId", equalTo("GE-0031"));
    }

    @Test
    void statsReturnsUnavailableOnException() {
        when(gardenClient.stats()).thenThrow(new RuntimeException("engine down"));

        given().when().get("/api/garden/stats")
                .then().statusCode(200)
                .body("available", equalTo(false));
    }
}
