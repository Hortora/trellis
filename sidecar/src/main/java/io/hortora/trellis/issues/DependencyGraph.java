package io.hortora.trellis.issues;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DependencyGraph {

    private final Set<String> nodes;
    private final Map<String, List<String>> forward;
    private final Map<String, List<String>> reverse;
    private final Set<String> closed;

    public DependencyGraph(Set<String> nodes, List<Dependency> edges) {
        this(nodes, edges, Set.of());
    }

    public DependencyGraph(Set<String> nodes, List<Dependency> edges, Set<String> closed) {
        this.nodes = Set.copyOf(nodes);
        this.closed = Set.copyOf(closed);
        this.forward = new HashMap<>();
        this.reverse = new HashMap<>();

        for (var node : nodes) {
            forward.put(node, new ArrayList<>());
            reverse.put(node, new ArrayList<>());
        }

        for (var dep : edges) {
            if (nodes.contains(dep.fromKey()) && nodes.contains(dep.toKey())) {
                forward.computeIfAbsent(dep.toKey(), k -> new ArrayList<>()).add(dep.fromKey());
                reverse.computeIfAbsent(dep.fromKey(), k -> new ArrayList<>()).add(dep.toKey());
            }
        }
    }


    public List<String> criticalPathFull() {
        var sorted = topologicalSort(nodes);
        if (sorted.isEmpty()) {return List.of();}

        var dist = new HashMap<String, Integer>();
        var pred = new HashMap<String, String>();

        for (var node : nodes) {dist.put(node, closed.contains(node) ? 0 : 1);}

        for (var node : sorted) {
            int d = dist.getOrDefault(node, 0);
            for (var succ : forward.getOrDefault(node, List.of())) {
                int succWeight = closed.contains(succ) ? 0 : 1;
                if (d + succWeight > dist.getOrDefault(succ, 0)) {
                    dist.put(succ, d + succWeight);
                    pred.put(succ, node);
                }
            }
        }

        String end     = null;
        int    maxDist = -1;
        for (var entry : dist.entrySet()) {
            if (entry.getValue() > maxDist) {
                maxDist = entry.getValue();
                end     = entry.getKey();
            }
        }

        var fullPath = new ArrayList<String>();
        for (var node = end; node != null; node = pred.get(node)) {
            fullPath.add(node);
        }
        Collections.reverse(fullPath);
        return fullPath;
    }

    public List<String> criticalPath() {
        return criticalPathFull().stream()
                                 .filter(n -> !closed.contains(n))
                                 .toList();}

    public List<DagNode> dagLayout() {
        var cycled  = cycleNodes();
        var acyclic = new HashSet<>(nodes);
        acyclic.removeAll(cycled);

        var sorted   = topologicalSort(acyclic);
        var critPath = new HashSet<>(criticalPathFull());

        var layerMap = new HashMap<String, Integer>();
        for (var node : sorted) {
            int maxPred = -1;
            for (var dep : reverse.getOrDefault(node, List.of())) {
                if (acyclic.contains(dep) && layerMap.containsKey(dep)) {
                    maxPred = Math.max(maxPred, layerMap.get(dep));
                }
            }
            layerMap.put(node, maxPred + 1);
        }

        var layerGroups = new HashMap<Integer, List<String>>();
        for (var entry : layerMap.entrySet()) {
            layerGroups.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        var result = new ArrayList<DagNode>();
        for (var entry : layerGroups.entrySet()) {
            var nodesInLayer = entry.getValue();
            for (int i = 0; i < nodesInLayer.size(); i++) {
                var key = nodesInLayer.get(i);
                result.add(new DagNode(key, entry.getKey(), i,
                                       closed.contains(key), critPath.contains(key), false, false));
            }
        }

        int cycleIndex = 0;
        for (var key : cycled) {
            result.add(new DagNode(key, -1, cycleIndex++,
                                   closed.contains(key), false, true, false));
        }

        return result;
    }


    public Set<String> cycleNodes() {
        var inDegree = new HashMap<String, Integer>();
        for (var node : nodes) inDegree.put(node, 0);

        for (var entry : reverse.entrySet()) {
            inDegree.put(entry.getKey(), entry.getValue().size());
        }

        var queue = new ArrayDeque<String>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        int consumed = 0;
        while (!queue.isEmpty()) {
            var node = queue.poll();
            consumed++;
            for (var succ : forward.getOrDefault(node, List.of())) {
                int newDeg = inDegree.get(succ) - 1;
                inDegree.put(succ, newDeg);
                if (newDeg == 0) queue.add(succ);
            }
        }

        if (consumed == nodes.size()) return Set.of();

        var cyclic = new HashSet<String>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() > 0) cyclic.add(entry.getKey());
        }
        return cyclic;
    }


    public Map<String, Integer> cascadeUnlockCounts() {
        var open   = openNodes();
        var result = new HashMap<String, Integer>();

        for (var node : open) {
            var simClosed = new HashSet<>(closed);
            simClosed.add(node);
            int count = 0;
            var queue = new ArrayDeque<String>();

            for (var succ : forward.getOrDefault(node, List.of())) {
                if (!open.contains(succ)) {continue;}
                if (allBlockersResolved(succ, simClosed)) {
                    queue.add(succ);
                }
            }

            while (!queue.isEmpty()) {
                var unlocked = queue.poll();
                count++;
                simClosed.add(unlocked);
                for (var succ : forward.getOrDefault(unlocked, List.of())) {
                    if (simClosed.contains(succ) || !open.contains(succ)) {continue;}
                    if (allBlockersResolved(succ, simClosed)) {
                        queue.add(succ);
                    }
                }
            }
            result.put(node, count);
        }
        return result;
    }

    private boolean allBlockersResolved(String node, Set<String> resolvedSet) {
        return reverse.getOrDefault(node, List.of()).stream()
                      .filter(nodes::contains)
                      .allMatch(resolvedSet::contains);
    }

    public List<String> bottlenecks() {
        var counts = cascadeUnlockCounts();
        return counts.entrySet().stream()
                     .filter(e -> e.getValue() > 1)
                     .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                     .map(Map.Entry::getKey)
                     .toList();}

    public Set<String> unblocked() {
        var open = openNodes();
        var result = new HashSet<String>();

        for (var node : open) {
            boolean allDepsResolved = reverse.getOrDefault(node, List.of()).stream()
                    .allMatch(dep -> closed.contains(dep) || !nodes.contains(dep));
            if (allDepsResolved) result.add(node);
        }

        return result;
    }

    private Set<String> openNodes() {
        var open = new HashSet<>(nodes);
        open.removeAll(closed);
        return open;
    }

    private List<String> topologicalSort(Set<String> subset) {
        var inDegree = new HashMap<String, Integer>();
        for (var node : subset) inDegree.put(node, 0);

        for (var node : subset) {
            for (var dep : reverse.getOrDefault(node, List.of())) {
                if (subset.contains(dep)) {
                    inDegree.merge(node, 1, Integer::sum);
                }
            }
        }

        var queue = new ArrayDeque<String>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        var sorted = new ArrayList<String>();
        while (!queue.isEmpty()) {
            var node = queue.poll();
            sorted.add(node);
            for (var succ : forward.getOrDefault(node, List.of())) {
                if (!subset.contains(succ)) continue;
                int newDeg = inDegree.get(succ) - 1;
                inDegree.put(succ, newDeg);
                if (newDeg == 0) queue.add(succ);
            }
        }

        return sorted;
    }
}
