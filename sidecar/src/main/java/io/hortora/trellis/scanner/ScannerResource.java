package io.hortora.trellis.scanner;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.file.Files;
import java.util.Map;

@Path("/api/workspace")
public class ScannerResource {

    @Inject
    WorkspaceScanner scanner;

    @Inject
    FileWatcherService watcherService;

    @GET
    @Path("/repo")
    @Produces(MediaType.APPLICATION_JSON)
    public Response repoDetail(@QueryParam("root") String root, @QueryParam("repo") String repoName) {
        if (root == null || root.isBlank() || repoName == null || repoName.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                           .entity(Map.of("error", "root and repo query parameters are required"))
                           .build();
        }
        var rootPath = resolveRoot(root);
        var cached = watcherService.currentModel(rootPath);
        if (cached == null) {
            return Response.status(Response.Status.NOT_FOUND)
                           .entity(Map.of("error", "workspace not scanned yet"))
                           .build();
        }
        var repo = cached.repos().stream()
                         .filter(r -> r.name().equals(repoName))
                         .findFirst()
                         .orElse(null);
        if (repo == null) {
            return Response.status(Response.Status.NOT_FOUND)
                           .entity(Map.of("error", "repo not found: " + repoName))
                           .build();
        }
        return Response.ok(repo).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response scan(@QueryParam("root") String root) {
        if (root == null || root.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                           .entity(Map.of("error", "root query parameter is required"))
                           .build();
        }

        var rootPath = resolveRoot(root);
        if (!Files.isDirectory(rootPath)) {
            return Response.status(Response.Status.NOT_FOUND)
                           .entity(Map.of("error", "root directory not found: " + root))
                           .build();
        }

        var cached = watcherService.currentModel(rootPath);
        if (cached != null) {
            return Response.ok(cached).build();
        }

        watcherService.watch(rootPath);
        return Response.ok(watcherService.currentModel(rootPath)).build();
    }

    static java.nio.file.Path resolveRoot(String root) {
        return io.hortora.trellis.util.PathUtil.resolveRoot(root);
    }
}
