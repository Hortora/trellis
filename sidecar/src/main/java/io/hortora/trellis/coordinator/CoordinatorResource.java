package io.hortora.trellis.coordinator;

import jakarta.enterprise.context.ApplicationScoped;
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

import java.util.List;
import java.util.Map;

@Path("/api/coordinator")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class CoordinatorResource {

    @Inject CoordinatorService service;
    @Inject CoordinatorConfig config;
    @Inject
            ActionService     actionService;


    @POST
    @Path("/message")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response message(CoordinatorMessageRequest request) {
        if (!config.enabled()) {
            return Response.status(503).entity(Map.of("error", "Coordinator disabled")).build();
        }
        try {
            var turn = service.handleMessage(request);
            return Response.ok(turn).build();
        } catch (Exception e) {
            return Response.status(503).entity(Map.of("error", e.getMessage(), "retryable", true)).build();
        }
    }

    @GET
    @Path("/conversation")
    public List<ConversationTurn> conversation(@QueryParam("workspace") String workspace) {
        return service.conversationHistory(workspace, config.maxConversationTurns());
    }

    @GET
    @Path("/advice")
    public List<CoordinatorAdvice> advice(@QueryParam("workspace") String workspace) {
        return service.recentAdvice(workspace);
    }

    @POST
    @Path("/advice/{id}/dismiss")
    public Response dismiss(@PathParam("id") String id) {
        service.dismissAdvice(id);
        return Response.noContent().build();
    }

    @GET
    @Path("/status")
    public CoordinatorStatus status() {
        return service.status();
    }

    @GET
    @Path("/actions")
    public List<ProposedAction> actions(@QueryParam("workspace") String workspace) {
        return actionService.pendingActions(workspace);
    }

    @GET
    @Path("/actions/history")
    public List<ProposedAction> actionHistory(@QueryParam("workspace") String workspace) {
        return actionService.actionHistory(workspace, 50);
    }

    @GET
    @Path("/actions/{id}")
    public Response getAction(@PathParam("id") String id) {
        var action = actionService.getAction(id);
        if (action == null) {return Response.status(404).entity(Map.of("error", "action not found")).build();}
        return Response.ok(action).build();
    }

    @POST
    @Path("/actions/{id}/approve")
    public Response approveAction(@PathParam("id") String id) {
        return actionOp(id, actionService::approve);
    }

    @POST
    @Path("/actions/{id}/reject")
    public Response rejectAction(@PathParam("id") String id) {
        return actionOp(id, actionService::reject);
    }

    @POST
    @Path("/actions/{id}/confirm")
    public Response confirmAction(@PathParam("id") String id) {
        return actionOp(id, actionService::confirm);
    }

    @POST
    @Path("/actions/{id}/cancel")
    public Response cancelAction(@PathParam("id") String id) {
        return actionOp(id, actionService::cancel);
    }

    private Response actionOp(String id, java.util.function.Function<String, ProposedAction> op) {
        try {
            var action = op.apply(id);
            if (action == null) {return Response.status(404).entity(Map.of("error", "action not found")).build();}
            return Response.ok(action).build();
        } catch (IllegalStateException e) {
            return Response.status(409).entity(Map.of("error", e.getMessage())).build();
        }
    }

}
