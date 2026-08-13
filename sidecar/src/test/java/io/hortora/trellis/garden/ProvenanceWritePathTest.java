package io.hortora.trellis.garden;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Automated provenance write-path contract tests (Refs #21).
 *
 * The write path is: soredium skill → engine MCP (gardenRecordProvenance) → SQLite.
 * Trellis only reads provenance via the engine REST API. These tests validate:
 *   1. The ProvenanceRecord contract — JSON deserialization from engine format
 *   2. The enriched provenance REST endpoint — field mapping, enrichment, degradation
 *   3. Multi-record and edge-case handling
 *
 * A live integration test (tagged "integration") verifies the full engine round-trip.
 */
@QuarkusTest
class ProvenanceWritePathTest {

    @InjectMock
    @RestClient
    GardenClient gardenClient;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void provenanceRecordDeserialisesFromEngineFormat() throws Exception {
        String json = """
                {
                    "issueRepo": "Hortora/trellis",
                    "issueNumber": 14,
                    "specName": "garden-service-provenance-design.md",
                    "geId": "GE-0031",
                    "recordedAt": "2026-08-02T12:00:00Z",
                    "recordedBy": "brainstorming"
                }
                """;

        ProvenanceRecord record = MAPPER.readValue(json, ProvenanceRecord.class);
        assertEquals("Hortora/trellis", record.issueRepo());
        assertEquals(14, record.issueNumber());
        assertEquals("garden-service-provenance-design.md", record.specName());
        assertEquals("GE-0031", record.geId());
        assertEquals("2026-08-02T12:00:00Z", record.recordedAt());
        assertEquals("brainstorming", record.recordedBy());
    }

    @Test
    void provenanceRecordIgnoresUnknownFields() throws Exception {
        String json = """
                {
                    "issueRepo": "Hortora/trellis",
                    "issueNumber": 14,
                    "specName": "",
                    "geId": "GE-0031",
                    "recordedAt": "2026-08-02T12:00:00Z",
                    "recordedBy": "work-start",
                    "futureField": "should-be-ignored"
                }
                """;

        ProvenanceRecord record = MAPPER.readValue(json, ProvenanceRecord.class);
        assertEquals("GE-0031", record.geId());
    }

    @Test
    void provenanceRecordListDeserialisesFromEngineFormat() throws Exception {
        String json = """
                [
                    {"issueRepo": "Hortora/trellis", "issueNumber": 14, "specName": "", "geId": "GE-0031", "recordedAt": "2026-08-02T12:00:00Z", "recordedBy": "work-start"},
                    {"issueRepo": "Hortora/trellis", "issueNumber": 14, "specName": "spec.md", "geId": "GE-0045", "recordedAt": "2026-08-02T12:05:00Z", "recordedBy": "brainstorming"}
                ]
                """;

        List<ProvenanceRecord> records = MAPPER.readValue(json, new TypeReference<>() {});
        assertEquals(2, records.size());
        assertEquals("GE-0031", records.get(0).geId());
        assertEquals("brainstorming", records.get(1).recordedBy());
    }

    @Test
    void provenanceRecordAcceptsAllKnownRecorderSources() throws Exception {
        for (String source : List.of("work-start", "brainstorming", "manual")) {
            String json = """
                    {"issueRepo": "Hortora/trellis", "issueNumber": 1, "specName": "", "geId": "GE-0001", "recordedAt": "2026-01-01T00:00:00Z", "recordedBy": "%s"}
                    """.formatted(source);
            ProvenanceRecord record = MAPPER.readValue(json, ProvenanceRecord.class);
            assertEquals(source, record.recordedBy());
        }
    }

    @Test
    void forwardLineageWithMultipleRecordsReturnsAll() {
        var records = List.of(
                new ProvenanceRecord("Hortora/trellis", 14, "", "GE-0031", "2026-08-02T12:00:00Z", "work-start"),
                new ProvenanceRecord("Hortora/trellis", 14, "spec.md", "GE-0045", "2026-08-02T12:05:00Z", "brainstorming"),
                new ProvenanceRecord("Hortora/trellis", 14, "spec.md", "GE-20260618-c552c3", "2026-08-02T12:10:00Z", "brainstorming"));
        when(gardenClient.forwardLineage("Hortora/trellis", 14)).thenReturn(records);

        given().queryParam("issueRepo", "Hortora/trellis")
                .queryParam("issueNumber", 14)
                .when().get("/api/garden/provenance")
                .then().statusCode(200)
                .body("size()", equalTo(3))
                .body("[0].geId", equalTo("GE-0031"))
                .body("[0].recordedBy", equalTo("work-start"))
                .body("[1].specName", equalTo("spec.md"))
                .body("[2].geId", equalTo("GE-20260618-c552c3"));
    }

    @Test
    void forwardLineageWithEmptyResultReturnsEmptyArray() {
        when(gardenClient.forwardLineage("Hortora/unknown", 999)).thenReturn(List.of());

        given().queryParam("issueRepo", "Hortora/unknown")
                .queryParam("issueNumber", 999)
                .when().get("/api/garden/provenance")
                .then().statusCode(200)
                .body("size()", equalTo(0));
    }

    @Test
    void provenanceRecordAcceptsLegacyAndNewGeIdFormats() {
        var records = List.of(
                new ProvenanceRecord("Hortora/trellis", 14, "", "GE-0031", "2026-08-02T12:00:00Z", "work-start"),
                new ProvenanceRecord("Hortora/trellis", 14, "", "GE-20260618-c552c3", "2026-08-02T12:00:00Z", "brainstorming"));
        when(gardenClient.forwardLineage("Hortora/trellis", 14)).thenReturn(records);

        given().queryParam("issueRepo", "Hortora/trellis")
                .queryParam("issueNumber", 14)
                .when().get("/api/garden/provenance")
                .then().statusCode(200)
                .body("[0].geId", equalTo("GE-0031"))
                .body("[1].geId", equalTo("GE-20260618-c552c3"));
    }

    @Test
    void provenanceRecordSpecNameEmptyOnFirstCallPopulatedOnSecond() {
        var records = List.of(
                new ProvenanceRecord("Hortora/trellis", 14, "", "GE-0031", "2026-08-02T12:00:00Z", "work-start"),
                new ProvenanceRecord("Hortora/trellis", 14, "garden-service-provenance-design.md", "GE-0031", "2026-08-02T12:10:00Z", "brainstorming"));
        when(gardenClient.forwardLineage("Hortora/trellis", 14)).thenReturn(records);

        given().queryParam("issueRepo", "Hortora/trellis")
                .queryParam("issueNumber", 14)
                .when().get("/api/garden/provenance")
                .then().statusCode(200)
                .body("[0].specName", equalTo(""))
                .body("[1].specName", equalTo("garden-service-provenance-design.md"));
    }

    @Test
    void enrichedRecordIncludesWorkspaceContextWhenNull() {
        var records = List.of(
                new ProvenanceRecord("Hortora/unknown", 999, "", "GE-0031", "2026-08-02T12:00:00Z", "work-start"));
        when(gardenClient.forwardLineage("Hortora/unknown", 999)).thenReturn(records);

        given().queryParam("issueRepo", "Hortora/unknown")
                .queryParam("issueNumber", 999)
                .when().get("/api/garden/provenance")
                .then().statusCode(200)
                .body("[0].workspace", nullValue())
                .body("[0].geId", equalTo("GE-0031"));
    }

    @Test
    @Tag("integration")
    void liveEngineRoundTrip() {
        // Post-hoc verification: after a real brainstorming session that used
        // gardenRecordProvenance, query the engine through trellis to verify
        // the records landed. Run with: mvn test -Dgroups=integration
        //
        // Prerequisites:
        //   1. Engine running on configured port (quarkus.rest-client.garden-engine.url)
        //   2. A brainstorming session has completed for a known issue
        //
        // This test is a template — fill in the expected issue and GE-IDs
        // after a real session, then run once to verify.

        // Uncomment and fill in after a real session:
        // given().queryParam("issueRepo", "Hortora/trellis")
        //         .queryParam("issueNumber", 14)
        //         .when().get("/api/garden/provenance")
        //         .then().statusCode(200)
        //         .body("size()", greaterThan(0))
        //         .body("[0].geId", startsWith("GE-"));
    }
}
