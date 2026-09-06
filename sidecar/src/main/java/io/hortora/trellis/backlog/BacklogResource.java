package io.hortora.trellis.backlog;

import io.hortora.trellis.scanner.FileWatcherService;
import io.hortora.trellis.scanner.WorkspaceRepos;
import io.hortora.trellis.worklog.BacklogEntry;
import io.hortora.trellis.worklog.WorklogService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
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
}
