package io.hortora.trellis;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api")
public class HealthResource {
    @jakarta.inject.Inject
    io.hortora.trellis.terminal.TerminalRegistry terminalRegistry;


    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GET
    @Path("/health/ready")
    @Produces(MediaType.APPLICATION_JSON)
    public jakarta.ws.rs.core.Response ready() {
        if (terminalRegistry.isBootstrapComplete()) {
            return jakarta.ws.rs.core.Response.ok(Map.of("status", "ready")).build();
        }
        return jakarta.ws.rs.core.Response.status(503).entity(Map.of("status", "starting")).build();
    }

}
