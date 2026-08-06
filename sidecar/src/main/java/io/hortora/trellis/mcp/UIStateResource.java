package io.hortora.trellis.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/model")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UIStateResource {

    @Inject
    UIStateStore store;

    @Inject
    ObjectMapper mapper;

    @SuppressWarnings("unchecked")
    @POST
    @Path("/ui-state")
    public Response updateUIState(String body) {
        if (body.length() > UIStateStore.MAX_CONTENT_SIZE) {
            return Response.status(413).entity(Map.of("error", "content too large")).build();
        }
        try {
            var state = (Map<String, Object>) mapper.readValue(body, Map.class);
            store.update(state);
            var correlationId = (String) state.get("correlationId");
            if (correlationId != null) {
                store.acknowledgeNavigation(correlationId, state);
            }
            return Response.noContent().build();
        } catch (Exception e) {
            return Response.status(400).entity(Map.of("error", "invalid JSON")).build();
        }
    }
}
