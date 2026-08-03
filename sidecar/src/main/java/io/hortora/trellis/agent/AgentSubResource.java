package io.hortora.trellis.agent;

import io.hortora.trellis.terminal.TerminalRegistry;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.Map;

@Produces(MediaType.APPLICATION_JSON)
public class AgentSubResource {

    private final String              terminalName;
    private final TerminalRegistry    registry;
    private final AgentProcessManager processManager;

    public AgentSubResource(String terminalName, TerminalRegistry registry,
                            AgentProcessManager processManager) {
        this.terminalName   = terminalName;
        this.registry       = registry;
        this.processManager = processManager;
    }

    @POST
    @Path("/start")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response start(StartAgentRequest request) {
        return executeLifecycle("start", () -> {
            var req = request != null ? request : new StartAgentRequest(false, null);
            req.validate();
            processManager.startAgent(terminalName, req);
        });
    }

    @POST
    @Path("/stop")
    public Response stop() {
        return executeLifecycle("stop", () -> processManager.stopAgent(terminalName));
    }

    @POST
    @Path("/pause")
    public Response pause() {
        return executeLifecycle("pause", () -> processManager.pauseAgent(terminalName));
    }

    @POST
    @Path("/resume")
    public Response resume() {
        return executeLifecycle("resume", () -> processManager.resumeAgent(terminalName));
    }

    @POST
    @Path("/refresh")
    public Response refresh() {
        return executeLifecycle("refresh", () -> processManager.refreshAgent(terminalName));
    }

    @GET
    @Path("/stats")
    public Response stats() {
        var terminal = registry.get(terminalName);
        if (terminal.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "terminal not found: " + terminalName)).build();
        }
        return Response.ok(processManager.getSnapshot(terminalName, terminal.get())).build();
    }

    private Response executeLifecycle(String operation, LifecycleAction action) {
        var terminal = registry.get(terminalName);
        if (terminal.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "terminal not found: " + terminalName)).build();
        }
        var lock = processManager.lockFor(terminalName);
        if (!lock.tryLock()) {
            return Response.status(409).entity(
                    Map.of("error", "operation already in progress for: " + terminalName)).build();
        }
        try {
            action.run();
            return Response.ok(processManager.getSnapshot(terminalName, terminal.get())).build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", e.getMessage())).build();
        } catch (IOException | InterruptedException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    interface LifecycleAction {
        void run() throws IOException, InterruptedException;
    }
}
