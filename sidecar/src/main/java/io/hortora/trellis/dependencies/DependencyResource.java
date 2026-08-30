package io.hortora.trellis.dependencies;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/dependencies")
@ApplicationScoped
public class DependencyResource {

    private final DependencyService service;

    @Inject
    public DependencyResource(DependencyService service) {
        this.service = service;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response get(@QueryParam("root") String root) {
        if (root == null || root.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        var graph = service.buildGraph(java.nio.file.Path.of(root));
        var result = new LinkedHashMap<String, Object>();
        result.put("criticalPath", graph.criticalPath().stream()
            .map(this::refToMap).toList());
        result.put("blocked", graph.grouped()
            .getOrDefault(IssueStatus.BLOCKED, List.of()).stream()
            .map(n -> nodeToMap(n, graph)).toList());
        result.put("unblocked", graph.grouped()
            .getOrDefault(IssueStatus.UNBLOCKED, List.of()).stream()
            .map(n -> nodeToMap(n, graph)).toList());
        result.put("clear", graph.grouped()
            .getOrDefault(IssueStatus.CLEAR, List.of()).stream()
            .map(n -> nodeToMap(n, graph)).toList());
        result.put("stats", Map.of(
            "totalIssues", graph.nodes().size(),
            "blocked", graph.grouped().getOrDefault(IssueStatus.BLOCKED, List.of()).size(),
            "unblocked", graph.grouped().getOrDefault(IssueStatus.UNBLOCKED, List.of()).size(),
            "clear", graph.grouped().getOrDefault(IssueStatus.CLEAR, List.of()).size(),
            "criticalPathDepth", graph.criticalPath().size()));
        return Response.ok(result).build();
    }

    private Map<String, Object> nodeToMap(DependencyNode node, DependencyGraph graph) {
        var map = new LinkedHashMap<String, Object>();
        map.put("number", node.ref().number());
        map.put("repo", node.ref().repo());
        map.put("title", node.title());
        map.put("issueState", node.issueState());
        map.put("status", node.status().name());
        map.put("blockedBy", node.blockedBy().stream()
            .map(b -> refWithState(b, graph.issueStates())).toList());
        map.put("blocking", node.blocking().stream().map(this::refToMap).toList());
        return map;
    }

    private Map<String, Object> refToMap(IssueRef ref) {
        return Map.of("number", ref.number(), "repo", ref.repo());
    }

    private Map<String, Object> refWithState(IssueRef ref, Map<IssueRef, String> issueStates) {
        var state = issueStates.getOrDefault(ref, "EXTERNAL");
        return Map.of("number", ref.number(), "repo", ref.repo(), "state", state);
    }
}
