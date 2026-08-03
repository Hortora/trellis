package io.hortora.trellis.agent;

import io.hortora.trellis.terminal.TerminalRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/agents/eviction")
@Produces(MediaType.APPLICATION_JSON)
public class EvictionResource {

    @Inject
    MemoryPressureMonitor monitor;

    @Inject
    TerminalRegistry registry;

    @Inject
    AgentProcessManager processManager;

    @GET
    public Response list() {
        var snapshots = processManager.getAllSnapshots(registry.list());
        return Response.ok(monitor.evaluate(snapshots)).build();
    }
}
