package io.hortora.trellis.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.io.IOException;
import java.io.InputStream;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

@Path("/api/projects")
@ApplicationScoped
public class BootstrapperResource {

    @Inject
    BootstrapRunner runner;

    @Inject
    ObjectMapper mapper;

    private volatile ProjectRegistry registry;

    private final Map<String, List<SseConnection>> sseClients = new ConcurrentHashMap<>();

    record SseConnection(SseEventSink sink, Sse sse) {}

    private ProjectRegistry getRegistry() throws IOException {
        var r = registry;
        if (r == null) {
            synchronized (this) {
                r = registry;
                if (r == null) {
                    try (InputStream is = Thread.currentThread().getContextClassLoader()
                            .getResourceAsStream("projects.json")) {
                        if (is == null) throw new IOException("projects.json not found on classpath");
                        var entries = mapper.readValue(is, new com.fasterxml.jackson.core.type.TypeReference<List<ProjectEntry>>() {});
                        r = ProjectRegistry.fromEntries(entries);
                        registry = r;
                    }
                }
            }
        }
        return r;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response listProjects() {
        try {
            return Response.ok(getRegistry().list()).build();
        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/{projectId}/bootstrap")
    @Produces(MediaType.APPLICATION_JSON)
    public Response startBootstrap(@PathParam("projectId") String projectId) {
        try {
            var project = getRegistry().findById(projectId);
            if (project.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Project not found: " + projectId)).build();
            }
            if (runner.isRunning(projectId)) {
                return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Bootstrap already running for " + projectId)).build();
            }

            java.nio.file.Path targetDir = java.nio.file.Path.of(System.getProperty("user.home"), "claude", projectId, "parent");

            Thread.ofVirtual().name("bootstrap-" + projectId).start(() ->
                runner.bootstrap(project.get(), targetDir));

            return Response.accepted(Map.of("status", "started", "projectId", projectId)).build();
        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/{projectId}/progress")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void streamProgress(@PathParam("projectId") String projectId,
                               @Context SseEventSink sink,
                               @Context Sse sse) {
        sseClients.computeIfAbsent(projectId, k -> new CopyOnWriteArrayList<>())
            .add(new SseConnection(sink, sse));
        sink.send(sse.newEvent("connected", projectId));
    }

    void onProgress(@ObservesAsync BootstrapProgress progress) {
        var clients = sseClients.get(progress.projectId());
        if (clients == null) return;

        clients.removeIf(c -> c.sink().isClosed());
        for (var client : clients) {
            try {
                client.sink().send(client.sse().newEventBuilder()
                    .name(progress.phase())
                    .data(progress.message())
                    .build());
                if (progress.terminal()) {
                    client.sink().close();
                }
            } catch (Exception ignored) {
                clients.remove(client);
            }
        }

        if (progress.terminal()) {
            sseClients.remove(progress.projectId());
        }
    }
}
