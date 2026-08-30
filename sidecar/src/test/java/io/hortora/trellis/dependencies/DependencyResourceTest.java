package io.hortora.trellis.dependencies;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DependencyResourceTest {

    @Test
    void returnsBadRequestWithoutRoot() {
        var service = mock(DependencyService.class);
        var resource = new DependencyResource(service);
        var response = resource.get(null);
        assertEquals(400, response.getStatus());
    }

    @Test
    void returnsBadRequestForBlankRoot() {
        var service = mock(DependencyService.class);
        var resource = new DependencyResource(service);
        var response = resource.get("  ");
        assertEquals(400, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsGraphData() {
        var service = mock(DependencyService.class);
        var blocked = new DependencyNode(new IssueRef(55, "R"), "Title", "OPEN",
            IssueStatus.BLOCKED, List.of(new IssueRef(42, "R")), List.of());
        var clear = new DependencyNode(new IssueRef(53, "R"), "Clear", "OPEN",
            IssueStatus.CLEAR, List.of(), List.of());
        var graph = new DependencyGraph(List.of(blocked, clear), List.of(),
            List.of(new IssueRef(42, "R"), new IssueRef(55, "R")),
            Map.of(IssueStatus.BLOCKED, List.of(blocked),
                   IssueStatus.UNBLOCKED, List.of(),
                   IssueStatus.CLEAR, List.of(clear)),
            Map.of(new IssueRef(42, "R"), "OPEN", new IssueRef(55, "R"), "OPEN",
                   new IssueRef(53, "R"), "OPEN"));
        when(service.buildGraph(Path.of("/root"))).thenReturn(graph);

        var resource = new DependencyResource(service);
        var response = resource.get("/root");
        assertEquals(200, response.getStatus());
        var entity = (Map<String, Object>) response.getEntity();
        assertNotNull(entity);
        var critPath = (List<?>) entity.get("criticalPath");
        assertEquals(2, critPath.size());
        var blockedList = (List<?>) entity.get("blocked");
        assertEquals(1, blockedList.size());
        var stats = (Map<String, Object>) entity.get("stats");
        assertEquals(1, stats.get("blocked"));
        assertEquals(1, stats.get("clear"));
    }
}
