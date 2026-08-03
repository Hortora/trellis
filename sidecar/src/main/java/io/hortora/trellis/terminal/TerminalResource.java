package io.hortora.trellis.terminal;

import io.hortora.trellis.agent.AgentProcessManager;
import io.hortora.trellis.agent.AgentSubResource;
import io.hortora.trellis.agent.StartAgentRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.Map;

@Path("/api/terminals")
@Produces(MediaType.APPLICATION_JSON)
public class TerminalResource {

    @Inject
    TerminalRegistry registry;

    @Inject
    AgentProcessManager processManager;

    @GET
    public Response list() {
        var snapshots = registry.list().stream()
                                .map(t -> processManager.getSnapshot(t.name(), t))
                                .toList();
        return Response.ok(snapshots).build();
    }

    @GET
    @Path("/{name}")
    public Response get(@PathParam("name") String name) {
        return registry.get(name)
                       .map(t -> Response.ok(processManager.getSnapshot(name, t)).build())
                       .orElse(Response.status(Response.Status.NOT_FOUND)
                                       .entity(Map.of("error", "terminal not found: " + name))
                                       .build());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(CreateTerminalRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                           .entity(Map.of("error", "name is required"))
                           .build();
        }
        if (registry.get(request.name()).isPresent()) {
            return Response.status(Response.Status.CONFLICT)
                           .entity(Map.of("error", "terminal already exists: " + request.name()))
                           .build();
        }
        try {
            String workDir = request.workingDir() != null ? request.workingDir() : "/tmp";
            registry.createSession(request.name(), workDir, request.slot(), request.repo(), request.issue());
            if (request.agent() != null) {
                processManager.startAgent(request.name(), request.agent());
            }
            var terminal = registry.get(request.name()).orElseThrow();
            return Response.status(Response.Status.CREATED)
                           .entity(processManager.getSnapshot(request.name(), terminal))
                           .build();
        } catch (IOException | InterruptedException e) {
            return Response.serverError()
                           .entity(Map.of("error", "failed to create terminal: " + e.getMessage()))
                           .build();
        }
    }

    @DELETE
    @Path("/{name}")
    public Response destroy(@PathParam("name") String name) {
        if (registry.get(name).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                           .entity(Map.of("error", "terminal not found: " + name))
                           .build();
        }
        try {
            processManager.stopAgent(name);
            processManager.clearState(name);
            registry.destroySession(name);
            return Response.noContent().build();
        } catch (IOException | InterruptedException e) {
            return Response.serverError()
                           .entity(Map.of("error", "failed to destroy terminal: " + e.getMessage()))
                           .build();
        }
    }

    @POST
    @Path("/{name}/input")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response sendInput(@PathParam("name") String name, String text) {
        if (registry.get(name).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                           .entity(Map.of("error", "terminal not found: " + name))
                           .build();
        }
        try {
            registry.sendKeys(name, text);
            return Response.noContent().build();
        } catch (IOException | InterruptedException e) {
            return Response.serverError()
                           .entity(Map.of("error", "failed to send input: " + e.getMessage()))
                           .build();
        }
    }

    @Path("/{name}/agent")
    public AgentSubResource agent(@PathParam("name") String name) {
        return new AgentSubResource(name, registry, processManager);
    }

    public record CreateTerminalRequest(String name, String workingDir, String slot,
                                        String repo, String issue, StartAgentRequest agent) {}
}
