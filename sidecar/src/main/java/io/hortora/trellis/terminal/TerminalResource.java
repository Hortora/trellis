package io.hortora.trellis.terminal;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.Map;

@Path("/api/sessions")
@Produces(MediaType.APPLICATION_JSON)
public class TerminalResource {

    @Inject
    SessionRegistry registry;

    @GET
    public Response list() {
        return Response.ok(registry.list()).build();
    }

    @GET
    @Path("/{name}")
    public Response get(@PathParam("name") String name) {
        return registry.get(name)
                .map(info -> Response.ok(info).build())
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "session not found: " + name))
                        .build());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(CreateSessionRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "name is required"))
                    .build();
        }
        if (registry.get(request.name()).isPresent()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "session already exists: " + request.name()))
                    .build();
        }
        try {
            String workDir = request.workingDir() != null ? request.workingDir() : "/tmp";
            registry.createSession(request.name(), workDir, request.slot(), request.repo(), request.issue());
            return Response.status(Response.Status.CREATED)
                    .entity(registry.get(request.name()).orElseThrow())
                    .build();
        } catch (IOException | InterruptedException e) {
            return Response.serverError()
                    .entity(Map.of("error", "failed to create session: " + e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/{name}")
    public Response destroy(@PathParam("name") String name) {
        if (registry.get(name).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "session not found: " + name))
                    .build();
        }
        try {
            registry.destroySession(name);
            return Response.noContent().build();
        } catch (IOException | InterruptedException e) {
            return Response.serverError()
                    .entity(Map.of("error", "failed to destroy session: " + e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/{name}/input")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response sendInput(@PathParam("name") String name, String text) {
        if (registry.get(name).isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "session not found: " + name))
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

    public record CreateSessionRequest(String name, String workingDir, String slot, String repo, String issue) {}
}
