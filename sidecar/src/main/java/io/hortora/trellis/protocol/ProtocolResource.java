package io.hortora.trellis.protocol;

import io.hortora.trellis.scanner.FileWatcherService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

@jakarta.ws.rs.Path("/api/protocols")
@jakarta.ws.rs.Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class ProtocolResource {

    @Inject
    ProtocolScanner scanner;

    @Inject
    ProtocolService service;

    @Inject
    FileWatcherService watcherService;

    @GET
    @jakarta.ws.rs.Path("/repos")
    public Response repos(@QueryParam("root") String root) {
        if (root == null || root.isBlank()) {
            return Response.status(400).entity(Map.of("error", "root required")).build();
        }
        try {
            java.nio.file.Path rootPath = io.hortora.trellis.util.PathUtil.resolveRoot(root);
            var model = watcherService.currentModel(rootPath);
            if (model == null) {
                return Response.status(404).entity(Map.of("error", "workspace not watched")).build();
            }
            var repos = scanner.findProtocolRepos(model.repos());
            return Response.ok(repos).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @jakarta.ws.rs.Path("/indexes")
    public Response indexes(@QueryParam("repo") String repo) {
        if (repo == null || repo.isBlank()) {
            return Response.status(400).entity(Map.of("error", "repo required")).build();
        }
        try {
            java.nio.file.Path repoPath = java.nio.file.Path.of(repo).toAbsolutePath().normalize();
            java.nio.file.Path protocolsDir = repoPath.resolve("docs/protocols");
            if (!Files.isDirectory(protocolsDir)) {
                return Response.ok(List.of()).build();
            }
            var indexes = scanner.findIndexes(protocolsDir);
            var relative = indexes.stream()
                    .map(p -> protocolsDir.relativize(p).toString())
                    .toList();
            return Response.ok(relative).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @jakarta.ws.rs.Path("/entries")
    public Response entries(@QueryParam("index") String index) {
        if (index == null || index.isBlank()) {
            return Response.status(400).entity(Map.of("error", "index required")).build();
        }
        try {
            java.nio.file.Path indexPath = java.nio.file.Path.of(index).toAbsolutePath().normalize();
            if (!isWithinProtocolsDir(indexPath)) {
                return Response.status(403).entity(Map.of("error", "path outside protocols directory")).build();
            }
            if (!Files.isRegularFile(indexPath)) {
                return Response.status(404).entity(Map.of("error", "index not found")).build();
            }
            var entries = scanner.parseIndex(indexPath);
            return Response.ok(entries).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST
    @jakarta.ws.rs.Path("/entries")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addEntry(AddEntryRequest request) {
        try {
            java.nio.file.Path indexPath = java.nio.file.Path.of(request.indexPath()).toAbsolutePath().normalize();
            if (!isWithinProtocolsDir(indexPath)) {
                return Response.status(403).entity(Map.of("error", "path outside protocols directory")).build();
            }
            service.addEntry(request);
            return Response.ok(Map.of("status", "added")).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @DELETE
    @jakarta.ws.rs.Path("/entries")
    public Response removeEntry(@QueryParam("index") String index, @QueryParam("file") String file) {
        if (index == null || file == null) {
            return Response.status(400).entity(Map.of("error", "index and file required")).build();
        }
        try {
            java.nio.file.Path indexPath = java.nio.file.Path.of(index).toAbsolutePath().normalize();
            if (!isWithinProtocolsDir(indexPath)) {
                return Response.status(403).entity(Map.of("error", "path outside protocols directory")).build();
            }
            service.removeEntry(indexPath, file);
            return Response.ok(Map.of("status", "removed")).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    private boolean isWithinProtocolsDir(java.nio.file.Path path) {
        try {
            java.nio.file.Path real = path.toRealPath();
            var model = watcherService.allModels().stream().findFirst().orElse(null);
            if (model == null) return false;
            return model.repos().stream().anyMatch(repo -> {
                java.nio.file.Path protocolsDir = repo.path().resolve("docs/protocols");
                try {
                    return Files.isDirectory(protocolsDir) && real.startsWith(protocolsDir.toRealPath());
                } catch (Exception e) {
                    return false;
                }
            });
        } catch (Exception e) {
            return false;
        }
    }
}
