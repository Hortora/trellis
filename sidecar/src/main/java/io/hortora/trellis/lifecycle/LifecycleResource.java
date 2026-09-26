package io.hortora.trellis.lifecycle;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Path("/api/lifecycle")
@Produces(MediaType.APPLICATION_JSON)
public class LifecycleResource {

    @Inject
    LifecycleManager manager;

    @Inject
    SlotAgentCoordinator coordinator;
    @Inject
    LifecycleOperationTracker tracker;


    @POST
    @Path("/start")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response start(StartRequest request) {
        return execute(() -> manager.start(
                java.nio.file.Path.of(request.workspaceRoot()), request.branch(), request.issue()));
    }

    @POST
    @Path("/end/{slotId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response end(@PathParam("slotId") String slotId, WorkspaceRequest request) {
        try {
            var progress = coordinator.coordinatedEndAsync(slotId,
                                                           java.nio.file.Path.of(request.workspaceRoot()));
            return Response.accepted(progress).build();
        } catch (ConcurrentOperationException e) {
            return Response.status(Response.Status.CONFLICT)
                           .entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/pause/{slotId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response pause(@PathParam("slotId") String slotId, WorkspaceRequest request) {
        try {
            var progress = coordinator.coordinatedPauseAsync(slotId,
                                                             java.nio.file.Path.of(request.workspaceRoot()));
            return Response.accepted(progress).build();
        } catch (ConcurrentOperationException e) {
            return Response.status(Response.Status.CONFLICT)
                           .entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/resume/{slotId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response resume(@PathParam("slotId") String slotId, WorkspaceRequest request) {
        try {
            var progress = coordinator.coordinatedResumeAsync(slotId,
                                                              java.nio.file.Path.of(request.workspaceRoot()));
            return Response.accepted(progress).build();
        } catch (ConcurrentOperationException e) {
            return Response.status(Response.Status.CONFLICT)
                           .entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @Path("/slot/create")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response slotCreate(SlotCreateRequest request) {
        return execute(() -> manager.slotCreate(java.nio.file.Path.of(request.workspaceRoot()), request.args()));
    }

    @POST
    @Path("/slot/{slotId}/merge")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response slotMerge(@PathParam("slotId") String slotId, WorkspaceRequest request) {
        return execute(() -> manager.slotMerge(slotId, java.nio.file.Path.of(request.workspaceRoot())));
    }

    @POST
    @Path("/epic/setup")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response epicSetup(SlotCreateRequest request) {
        return execute(() -> manager.epicSetup(java.nio.file.Path.of(request.workspaceRoot()), request.args()));
    }

    @POST
    @Path("/epic/{epicPath}/next")
    public Response epicNext(@PathParam("epicPath") String epicPath) {
        return execute(() -> manager.epicNext(epicPath));
    }


    @GET
    @Path("/operations/{operationId}")
    public Response getOperation(@PathParam("operationId") String operationId) {
        var progress = tracker.getProgress(operationId);
        if (progress == null) {return Response.status(Response.Status.NOT_FOUND).build();}
        return Response.ok(progress).build();
    }

    @GET
    @Path("/operations")
    public Response getOperationBySlot(@QueryParam("slot") String slotId) {
        var progress = tracker.getActiveOperation(slotId);
        if (progress == null) {return Response.noContent().build();}
        return Response.ok(progress).build();
    }

    private Response execute(LifecycleOperation operation) {
        try {
            var result = operation.run();
            if (result.success()) {
                return Response.ok(result).build();
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                           .entity(result).build();
        } catch (ConcurrentOperationException e) {
            return Response.status(Response.Status.CONFLICT)
                           .entity(Map.of("error", e.getMessage())).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.serverError()
                           .entity(Map.of("error", e.getMessage())).build();
        } catch (IOException e) {
            return Response.serverError()
                           .entity(Map.of("error", e.getMessage())).build();
        }
    }

    @FunctionalInterface
    interface LifecycleOperation {
        OperationResult run() throws IOException, InterruptedException, ConcurrentOperationException;
    }

    public record StartRequest(String workspaceRoot, String branch, String issue) {}

    public record WorkspaceRequest(String workspaceRoot) {}

    public record SlotCreateRequest(String workspaceRoot, List<String> args) {}
}
