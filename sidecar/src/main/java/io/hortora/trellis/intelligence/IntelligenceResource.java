package io.hortora.trellis.intelligence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/intelligence")
@ApplicationScoped
public class IntelligenceResource {

    private final WorkIntelligenceModelProvider provider;

    @Inject
    public IntelligenceResource(WorkIntelligenceModelProvider provider) {
        this.provider = provider;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@QueryParam("root") String root) {
        if (root == null || root.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        return Response.ok(provider.summary()).build();
    }
}
