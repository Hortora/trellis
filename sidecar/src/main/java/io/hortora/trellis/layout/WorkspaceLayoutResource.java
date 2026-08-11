package io.hortora.trellis.layout;

import io.hortora.trellis.util.PathUtil;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.Map;

@Path("/api/workspace")
public class WorkspaceLayoutResource {

    @Inject
    WorkspaceLayoutStore store;

    @GET
    @Path("/layout")
    @Produces(MediaType.APPLICATION_JSON)
    public Response loadLayout(@QueryParam("root") String root) {
        if (root == null || root.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "root query parameter is required"))
                    .build();
        }
        try {
            var json = store.loadLayout(PathUtil.resolveRoot(root));
            if (json == null) return Response.ok("{}").build();
            return Response.ok(json).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/layout")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveLayout(@QueryParam("root") String root, String body) {
        return doSaveLayout(root, body);
    }

    @POST
    @Path("/layout")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveLayoutPost(@QueryParam("root") String root, String body) {
        return doSaveLayout(root, body);
    }

    private Response doSaveLayout(String root, String body) {
        if (root == null || root.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "root query parameter is required"))
                    .build();
        }
        try {
            store.saveLayout(PathUtil.resolveRoot(root), body);
            return Response.noContent().build();
        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/groups")
    @Produces(MediaType.APPLICATION_JSON)
    public Response loadGroups(@QueryParam("root") String root) {
        if (root == null || root.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "root query parameter is required"))
                    .build();
        }
        try {
            var json = store.loadGroups(PathUtil.resolveRoot(root));
            if (json == null) return Response.ok("{\"groups\":[]}").type(MediaType.APPLICATION_JSON).build();
            return Response.ok(json).type(MediaType.APPLICATION_JSON).build();
        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    @PUT
    @Path("/groups")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveGroups(@QueryParam("root") String root, String body) {
        if (root == null || root.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "root query parameter is required"))
                    .build();
        }
        try {
            store.saveGroups(PathUtil.resolveRoot(root), body);
            return Response.noContent().build();
        } catch (IOException e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }
}
