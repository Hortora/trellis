package io.hortora.trellis.issues;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DependencyGraphTest {

    // --- Linear chain ---

    @Test
    void criticalPathOfLinearChain() {
        // A -> B -> C (A blocks B, B blocks C)
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("B", "A"), dep("C", "B"))
        );

        var path = graph.criticalPath();

        assertEquals(List.of("A", "B", "C"), path);
    }

    // --- Diamond ---

    @Test
    void criticalPathOfDiamond() {
        // A -> B, A -> C, B -> D, C -> D
        // Two paths: A-B-D (len 3) and A-C-D (len 3) — both equal
        var graph = new DependencyGraph(
                Set.of("A", "B", "C", "D"),
                List.of(dep("B", "A"), dep("C", "A"), dep("D", "B"), dep("D", "C"))
        );

        var path = graph.criticalPath();

        assertEquals(3, path.size());
        assertEquals("A", path.getFirst());
        assertEquals("D", path.getLast());
    }

    // --- Parallel branches ---

    @Test
    void criticalPathPicksLongestBranch() {
        // A -> B -> C -> D (length 4)
        // A -> E (length 2)
        var graph = new DependencyGraph(
                Set.of("A", "B", "C", "D", "E"),
                List.of(dep("B", "A"), dep("C", "B"), dep("D", "C"), dep("E", "A"))
        );

        var path = graph.criticalPath();

        assertEquals(List.of("A", "B", "C", "D"), path);
    }

    // --- Closed nodes ---

    @Test
    void closedNodesExcludedFromCriticalPath() {
        // A -> B -> C, but B is closed
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("B", "A"), dep("C", "B")),
                Set.of("B")
        );

        var path = graph.criticalPath();

        assertEquals(List.of("A", "C"), path);
    }

    // --- Single node ---

    @Test
    void singleNodeCriticalPath() {
        var graph = new DependencyGraph(Set.of("A"), List.of());

        var path = graph.criticalPath();

        assertEquals(List.of("A"), path);
    }

    // --- No edges ---

    @Test
    void disconnectedNodesReturnSingleNode() {
        var graph = new DependencyGraph(Set.of("A", "B", "C"), List.of());

        var path = graph.criticalPath();

        assertEquals(1, path.size());
    }

    // --- Cycle detection ---

    @Test
    void detectsCycles() {
        // A -> B -> C -> A
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("B", "A"), dep("C", "B"), dep("A", "C"))
        );

        var cycles = graph.cycleNodes();

        assertFalse(cycles.isEmpty());
    }

    @Test
    void noCyclesInValidDag() {
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("B", "A"), dep("C", "B"))
        );

        assertTrue(graph.cycleNodes().isEmpty());
    }

    // --- Bottlenecks ---

    @Test
    void bottleneckRankedByUnlockingImpact() {
        // A blocks B, C, D — A unlocks 3 issues
        // B blocks E — B unlocks 1
        var graph = new DependencyGraph(
                Set.of("A", "B", "C", "D", "E"),
                List.of(dep("B", "A"), dep("C", "A"), dep("D", "A"), dep("E", "B"))
        );

        var bottlenecks = graph.bottlenecks();

        assertFalse(bottlenecks.isEmpty());
        assertEquals("A", bottlenecks.getFirst());
    }

    @Test
    void bottlenecksExcludeClosedNodes() {
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("B", "A"), dep("C", "A")),
                Set.of("A")
        );

        var bottlenecks = graph.bottlenecks();

        assertFalse(bottlenecks.contains("A"));
    }

    // --- Unblocked ---

    @Test
    void unblockedReturnsNodesWithNoPendingDeps() {
        // A has no deps, B depends on A (done), C depends on B (pending)
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("B", "A"), dep("C", "B")),
                Set.of("A")
        );

        var unblocked = graph.unblocked();

        assertTrue(unblocked.contains("B"));
        assertFalse(unblocked.contains("C"));
        assertFalse(unblocked.contains("A"));
    }

    // --- Helpers ---

    private Dependency dep(String from, String to) {
        return new Dependency(from, to, false);
    }
}
