package io.hortora.trellis.garden;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@RegisterRestClient(configKey = "garden-engine")
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public interface GardenClient {

    @GET
    @Path("/search/adaptive")
    AdaptiveSearchResponse search(
            @QueryParam("q") String query,
            @QueryParam("keywords") String keywords,
            @QueryParam("domain") List<String> domains,
            @QueryParam("type") String type,
            @QueryParam("tags") String tags,
            @QueryParam("limit") Integer limit);

    @GET
    @Path("/entries/{id}")
    EntryDetail getEntry(@PathParam("id") String geId);

    @GET
    @Path("/provenance")
    List<ProvenanceRecord> forwardLineage(
            @QueryParam("issueRepo") String repo,
            @QueryParam("issueNumber") int number);

    @GET
    @Path("/provenance/reverse")
    List<ProvenanceRecord> reverseLineage(@QueryParam("geId") String geId);

    @GET
    @Path("/provenance/stats")
    ProvenanceStats stats();
}
