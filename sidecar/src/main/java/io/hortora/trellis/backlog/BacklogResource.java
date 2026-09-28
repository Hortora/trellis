package io.hortora.trellis.backlog;

import io.hortora.trellis.scanner.FileWatcherService;
import io.hortora.trellis.scanner.WorkspaceRepos;
import io.hortora.trellis.worklog.BacklogEntry;
import io.hortora.trellis.worklog.WorklogService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.stream.Collectors;

@Path("/api/backlog")
@Produces(MediaType.APPLICATION_JSON)
public class BacklogResource {

    @Inject
    WorklogService worklogService;

    @Inject
    FileWatcherService fileWatcherService;

    @GET
    public List<BacklogEntry> list(@QueryParam("repo") String repo,
                                   @QueryParam("root") String root) {
        if (repo != null && !repo.isBlank()) {
            return worklogService.backlogEntries(repo);
        }
        if (root != null && !root.isBlank()) {
            var wsRepos = WorkspaceRepos.resolve(fileWatcherService, root);
            if (wsRepos.isEmpty()) return worklogService.backlogEntries(null);
            var all = worklogService.backlogEntries(null);
            var issueRepos = all.stream().map(BacklogEntry::issueRepo).collect(Collectors.toSet());
            var matched = WorkspaceRepos.matchIssueRepos(wsRepos, issueRepos);
            return all.stream()
                    .filter(e -> matched.contains(e.issueRepo()))
                    .toList();
        }
        return worklogService.backlogEntries(null);
    }

    @GET
    @Path("/body")
    public jakarta.ws.rs.core.Response body(@QueryParam("repo") String repo,
                                            @QueryParam("number") int number) {
        if (repo == null || repo.isBlank() || number <= 0) {
            return jakarta.ws.rs.core.Response.status(400).build();
        }
        var body = worklogService.issueBody(repo, number);
        if (body == null) {
            return jakarta.ws.rs.core.Response.status(404).build();
        }
        return jakarta.ws.rs.core.Response.ok(java.util.Map.of("body", body)).build();
    }

}
