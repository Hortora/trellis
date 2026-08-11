package io.hortora.trellis.backlog;

import io.hortora.trellis.worklog.BacklogEntry;
import io.hortora.trellis.worklog.WorklogService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/backlog")
@Produces(MediaType.APPLICATION_JSON)
public class BacklogResource {

    @Inject
    WorklogService worklogService;

    @GET
    public List<BacklogEntry> list(@QueryParam("repo") String repo) {
        return worklogService.backlogEntries(repo);
    }
}
