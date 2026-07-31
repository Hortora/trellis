package io.hortora.trellis.issues;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void bottleneckRankedByUnlockingImpact() {// A blocks B, C, D — cascade count = 4 (B, C, D, E via B)
// B blocks E — cascade count = 1 (below threshold)
        var graph = new DependencyGraph(
                Set.of("A", "B", "C", "D", "E"),
                List.of(dep("B", "A"), dep("C", "A"), dep("D", "A"), dep("E", "B"))
        );

        var bottlenecks = graph.bottlenecks();

        assertEquals(List.of("A"), bottlenecks);}

    @Test
    void bottlenecksExcludeClosedNodes() {// A blocks B and C, but A is closed. B and C are already unblocked.
// No open node has cascade count > 1.
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("B", "A"), dep("C", "A")),
                Set.of("A")
        );

        var bottlenecks = graph.bottlenecks();

        assertTrue(bottlenecks.isEmpty());}

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


// --- Cascade unlock counts ---

    @Test
    void cascadeUnlockLinearChain() {
        // A -> B -> C: completing A unlocks B, which cascades to unlock C
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("B", "A"), dep("C", "B"))
        );
        var counts = graph.cascadeUnlockCounts();
        assertEquals(2, counts.getOrDefault("A", 0));
        assertEquals(1, counts.getOrDefault("B", 0));
        assertEquals(0, counts.getOrDefault("C", 0));
    }

    @Test
    void cascadeUnlockDiamondDoesNotOverCount() {// A -> B, A -> C, B -> D, C -> D
// Completing A: B and C unblocked (sole blocker). Both added to sim-closed.
// D's blockers (B, C) now both in sim-closed → D unblocked. Cascade for A = 3.
        var graph = new DependencyGraph(
                Set.of("A", "B", "C", "D"),
                List.of(dep("B", "A"), dep("C", "A"), dep("D", "B"), dep("D", "C"))
        );
        var counts = graph.cascadeUnlockCounts();
        assertEquals(3, counts.getOrDefault("A", 0));}

    @Test
    void cascadeUnlockWithClosedNodes() {
        // A -> B -> C, A is closed. B is already unblocked.
        // Completing B: unlocks C. Cascade = 1.
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("B", "A"), dep("C", "B")),
                Set.of("A")
        );
        var counts = graph.cascadeUnlockCounts();
        assertEquals(0, counts.getOrDefault("A", 0));
        assertEquals(1, counts.getOrDefault("B", 0));
    }

    @Test
    void cascadeUnlockMultiBlockerNotUnlocked() {
        // A -> C, B -> C (C has two open blockers)
        // Completing A alone does NOT unblock C. Cascade for A = 0.
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("C", "A"), dep("C", "B"))
        );
        var counts = graph.cascadeUnlockCounts();
        assertEquals(0, counts.getOrDefault("A", 0));
        assertEquals(0, counts.getOrDefault("B", 0));
    }

    @Test
    void cascadeUnlockLastBlocker() {
        // A -> C, B -> C. B is closed.
        // A is the last open blocker of C. Completing A unlocks C.
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("C", "A"), dep("C", "B")),
                Set.of("B")
        );
        var counts = graph.cascadeUnlockCounts();
        assertEquals(1, counts.getOrDefault("A", 0));
    }


// --- criticalPathFull ---

    @Test
    void criticalPathFullIncludesClosedNodes() {
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("B", "A"), dep("C", "B")),
                Set.of("B")
        );
        var full = graph.criticalPathFull();
        assertEquals(List.of("A", "B", "C"), full);

        var open = graph.criticalPath();
        assertEquals(List.of("A", "C"), open);
    }

// --- dagLayout ---

    @Test
    void dagLayoutAssignsLayers() {
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("B", "A"), dep("C", "B"))
        );
        var layout = graph.dagLayout();
        assertEquals(3, layout.size());

        var byKey = layout.stream().collect(java.util.stream.Collectors.toMap(DagNode::key, n -> n));
        assertTrue(byKey.get("A").layer() < byKey.get("B").layer());
        assertTrue(byKey.get("B").layer() < byKey.get("C").layer());
    }

    @Test
    void dagLayoutMarksCriticalPath() {
        var graph = new DependencyGraph(
                Set.of("A", "B", "C", "D", "E"),
                List.of(dep("B", "A"), dep("C", "B"), dep("D", "C"), dep("E", "A"))
        );
        var layout = graph.dagLayout();
        var byKey  = layout.stream().collect(java.util.stream.Collectors.toMap(DagNode::key, n -> n));

        assertTrue(byKey.get("A").onCriticalPath());
        assertTrue(byKey.get("B").onCriticalPath());
        assertTrue(byKey.get("C").onCriticalPath());
        assertTrue(byKey.get("D").onCriticalPath());
        assertFalse(byKey.get("E").onCriticalPath());
    }

    @Test
    void dagLayoutHandlesCycles() {
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("B", "A"), dep("C", "B"), dep("A", "C"))
        );
        var layout = graph.dagLayout();
        for (var node : layout) {
            assertTrue(node.inCycle());
            assertEquals(-1, node.layer());
        }
    }

    @Test
    void dagLayoutClosedNodesMarked() {
        var graph = new DependencyGraph(
                Set.of("A", "B"),
                List.of(dep("B", "A")),
                Set.of("A")
        );
        var layout = graph.dagLayout();
        var byKey  = layout.stream().collect(java.util.stream.Collectors.toMap(DagNode::key, n -> n));
        assertTrue(byKey.get("A").closed());
        assertFalse(byKey.get("B").closed());
    }

    @Test
    void criticalPathEmptyWhenAllNodesClosed() {
        var graph = new DependencyGraph(
                Set.of("A", "B", "C"),
                List.of(dep("B", "A"), dep("C", "B")),
                Set.of("A", "B", "C")
        );

        var path = graph.criticalPath();
        assertTrue(path.isEmpty());

        var counts = graph.cascadeUnlockCounts();
        assertTrue(counts.isEmpty());

        assertTrue(graph.unblocked().isEmpty());
        assertTrue(graph.bottlenecks().isEmpty());
    }

    private Dependency dep(String from, String to) {
        return new Dependency(from, to, false);
    }
}
