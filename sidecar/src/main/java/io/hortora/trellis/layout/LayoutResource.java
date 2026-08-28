package io.hortora.trellis.layout;

import io.hortora.trellis.util.PathUtil;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

@Path("/api/layouts")
public class LayoutResource {

    private static final Pattern SAFE_KEY = Pattern.compile("[a-zA-Z0-9_-]+");

    @Inject
    LayoutStore store;

    @GET
    @Path("/{key}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@PathParam("key") String key, @QueryParam("root") String root) {
        if (!SAFE_KEY.matcher(key).matches()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "key must be alphanumeric, hyphens, or underscores"))
                    .build();
        }
        if (root == null || root.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "root query parameter is required"))
                    .build();
        }
        try {
            var json = store.load(PathUtil.resolveRoot(root), key);
            if (json == null) return Response.status(Response.Status.NOT_FOUND).build();
            return Response.ok(json).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/{key}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response put(@PathParam("key") String key, @QueryParam("root") String root, String body) {
        if (!SAFE_KEY.matcher(key).matches()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "key must be alphanumeric, hyphens, or underscores"))
                    .build();
        }
        if (root == null || root.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "root query parameter is required"))
                    .build();
        }
        try {
            store.save(PathUtil.resolveRoot(root), key, body);
            return Response.noContent().build();
        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @DELETE
    @Path("/{key}")
    public Response delete(@PathParam("key") String key, @QueryParam("root") String root) {
        if (!SAFE_KEY.matcher(key).matches()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "key must be alphanumeric, hyphens, or underscores"))
                    .build();
        }
        if (root == null || root.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "root query parameter is required"))
                    .build();
        }
        try {
            store.delete(PathUtil.resolveRoot(root), key);
            return Response.noContent().build();
        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }
}
