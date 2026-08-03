package io.hortora.trellis.artifact;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

@Path("/api/artifacts")
@Produces(MediaType.APPLICATION_JSON)
public class ArtifactResource {

    private static final long MAX_CONTENT_SIZE = 1_048_576;

    @Inject
    ArtifactScanner scanner;

    @GET
    public Response list(@QueryParam("root") String root) {
        if (root == null || root.isBlank()) {
            return Response.status(400).entity(Map.of("error", "root query parameter is required")).build();
        }
        var rootPath = java.nio.file.Path.of(root);
        if (!Files.isDirectory(rootPath)) {
            return Response.status(404).entity(Map.of("error", "root directory not found: " + root)).build();
        }
        return Response.ok(scanner.scan(rootPath)).build();
    }

    @GET
    @Path("/content")
    @Produces(MediaType.TEXT_PLAIN)
    public Response content(@QueryParam("root") String root, @QueryParam("path") String path) {
        if (root == null || root.isBlank() || path == null || path.isBlank()) {
            return Response.status(400).entity("root and path query parameters are required").build();
        }
        var rootPath = java.nio.file.Path.of(root);
        if (!Files.isDirectory(rootPath)) {
            return Response.status(404).entity("root directory not found").build();
        }

        var filePath = java.nio.file.Path.of(path);
        if (!Files.isRegularFile(filePath)) {
            return Response.status(404).entity("file not found").build();
        }

        try {
            var realPath = filePath.toRealPath();
            var realRoot = rootPath.toRealPath();
            java.nio.file.Path realProj = null;
            var projLink = rootPath.resolve("proj");
            if (Files.isSymbolicLink(projLink)) {
                try {
                    realProj = projLink.toRealPath();
                } catch (IOException ignored) {
                }
            }

            boolean withinWorkspace = realPath.startsWith(realRoot);
            boolean withinProject = realProj != null && realPath.startsWith(realProj);
            if (!withinWorkspace && !withinProject) {
                return Response.status(403).entity("path outside allowed roots").build();
            }

            if (Files.size(filePath) > MAX_CONTENT_SIZE) {
                return Response.status(413).entity("file exceeds 1 MB limit").build();
            }

            return Response.ok(Files.readString(filePath)).build();
        } catch (IOException e) {
            return Response.serverError().entity("failed to read file: " + e.getMessage()).build();
        }
    }
}
