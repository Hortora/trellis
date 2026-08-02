package io.hortora.trellis.garden;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.Map;

@Path("/api/garden")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class GardenResource {

    @Inject @RestClient GardenClient gardenClient;
    @Inject ProvenanceEnricher enricher;

    @GET
    @Path("/search")
    public Response search(
            @QueryParam("q") String query,
            @QueryParam("keywords") String keywords,
            @QueryParam("domain") List<String> domains,
            @QueryParam("type") String type,
            @QueryParam("tags") String tags,
            @QueryParam("limit") Integer limit) {
        try {
            AdaptiveSearchResponse response = gardenClient.search(query, keywords, domains, type, tags, limit);
            return Response.ok(response).build();
        } catch (Exception e) {
            Log.warn("Garden search failed", e);
            return Response.ok(Map.of("available", false, "error", "Garden engine unavailable")).build();
        }
    }

    @GET
    @Path("/entries")
    public Response getEntry(@QueryParam("id") String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return Response.status(400).entity(Map.of("error", "id query parameter is required")).build();
        }
        String geId = normalizeGeId(rawId);
        try {
            EntryDetail entry = gardenClient.getEntry(geId);
            return Response.ok(entry).build();
        } catch (Exception e) {
            Log.warn("Garden entry lookup failed for " + geId, e);
            return Response.status(404).entity(Map.of("error", "Entry not found or engine unavailable")).build();
        }
    }

    private static String normalizeGeId(String raw) {
        String withoutExt = raw.replaceFirst("\\.md$", "");
        if (withoutExt.contains("/")) {
            return withoutExt.substring(withoutExt.lastIndexOf('/') + 1);
        }
        return withoutExt;
    }

    @GET
    @Path("/provenance")
    public Response forwardLineage(
            @QueryParam("issueRepo") String issueRepo,
            @QueryParam("issueNumber") int issueNumber) {
        try {
            List<ProvenanceRecord> records = gardenClient.forwardLineage(issueRepo, issueNumber);
            List<EnrichedProvenanceRecord> enriched = enricher.enrich(records);
            return Response.ok(enriched).build();
        } catch (Exception e) {
            Log.warn("Forward lineage failed", e);
            return Response.ok(Map.of("available", false, "error", "Garden engine unavailable")).build();
        }
    }

    @GET
    @Path("/provenance/reverse")
    public Response reverseLineage(@QueryParam("geId") String geId) {
        try {
            List<ProvenanceRecord> records = gardenClient.reverseLineage(geId);
            List<EnrichedProvenanceRecord> enriched = enricher.enrich(records);
            return Response.ok(enriched).build();
        } catch (Exception e) {
            Log.warn("Reverse lineage failed", e);
            return Response.ok(Map.of("available", false, "error", "Garden engine unavailable")).build();
        }
    }

    @GET
    @Path("/stats")
    public Response stats() {
        try {
            ProvenanceStats stats = gardenClient.stats();
            return Response.ok(stats).build();
        } catch (Exception e) {
            Log.warn("Provenance stats failed", e);
            return Response.ok(Map.of("available", false, "error", "Garden engine unavailable")).build();
        }
    }
}
