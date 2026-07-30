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
    @Produces(MediaType.APPLICATION_JSON)
    public Response scan(@QueryParam("root") String root) {
        if (root == null || root.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                           .entity(Map.of("error", "root query parameter is required"))
                           .build();
        }

        var rootPath = java.nio.file.Path.of(root);
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
}
