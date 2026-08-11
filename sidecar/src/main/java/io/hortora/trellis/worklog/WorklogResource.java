package io.hortora.trellis.worklog;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/worklog")
@Produces(MediaType.APPLICATION_JSON)
public class WorklogResource {

    @Inject
    WorklogService worklogService;

    @GET
    @Path("/events")
    public List<WorklogEvent> events(
            @QueryParam("since") String since,
            @QueryParam("type") String type,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return worklogService.recentEvents(since, type, limit);
    }

    @GET
    @Path("/work-items")
    public List<WorkItem> workItems() {
        return worklogService.activeWork();
    }

    @GET
    @Path("/work-items/{branch}/timeline")
    public Response timeline(
            @PathParam("branch") String branch,
            @QueryParam("repoPath") String repoPath) {
        if (repoPath == null || repoPath.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"repoPath query parameter is required\"}")
                    .build();
        }
        return Response.ok(worklogService.workItemTimeline(branch, repoPath)).build();
    }

    @GET
    @Path("/slots")
    public List<SlotInfo> slots(@QueryParam("familyRoot") String familyRoot) {
        return worklogService.slotStatus(familyRoot);
    }
}
