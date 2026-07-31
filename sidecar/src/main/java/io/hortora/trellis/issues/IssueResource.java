package io.hortora.trellis.issues;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/api/repos/{owner}/{repo}/issues")
@Produces(MediaType.APPLICATION_JSON)
public class IssueResource {

    @Inject
    IssueEngine engine;

    @GET
    public Response listIssues(@PathParam("owner") String owner, @PathParam("repo") String repo) {
        var issues = engine.fetchIssues(owner, repo);
        return Response.ok(issues).build();
    }

}
