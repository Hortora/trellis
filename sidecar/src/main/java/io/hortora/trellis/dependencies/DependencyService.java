package io.hortora.trellis.dependencies;

import io.hortora.trellis.mcp.GenerationCounter;
import io.hortora.trellis.scanner.FileWatcherService;
import io.hortora.trellis.worklog.WorklogService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ApplicationScoped
public class DependencyService {

    private static final Pattern SSH_URL = Pattern.compile("git@github\\.com:(.+/.+?)(?:\\.git)?$");
    private static final Pattern HTTPS_URL = Pattern.compile("https://github\\.com/(.+/.+?)(?:\\.git)?$");
    private static final DependencyGraph EMPTY = new DependencyGraph(
        List.of(), List.of(), List.of(), Map.of(
            IssueStatus.BLOCKED, List.of(),
            IssueStatus.UNBLOCKED, List.of(),
            IssueStatus.CLEAR, List.of()),
        Map.of());

    private final WorklogService worklogService;
    private final FileWatcherService fileWatcherService;
    private final GenerationCounter generation;

    private volatile DependencyGraph cachedGraph;
    private volatile long cachedGeneration = -1;

    @Inject
    public DependencyService(WorklogService worklogService,
                             FileWatcherService fileWatcherService,
                             GenerationCounter generation) {
        this.worklogService = worklogService;
        this.fileWatcherService = fileWatcherService;
        this.generation = generation;
    }

    public DependencyGraph buildGraph(Path workspaceRoot) {
        long gen = generation.current();
        if (cachedGraph != null && gen == cachedGeneration) return cachedGraph;

        var model = fileWatcherService.currentModel(workspaceRoot);
        if (model == null) return EMPTY;

        var repos = model.repos().stream()
            .map(r -> extractOwnerRepo(r.remoteUrl()))
            .filter(Objects::nonNull)
            .toList();
        if (repos.isEmpty()) return EMPTY;

        var issueData = worklogService.issueDependencyData(repos);
        if (issueData.isEmpty()) return EMPTY;

        var stateMap = new HashMap<IssueRef, String>();
        var titleMap = new HashMap<IssueRef, String>();
        for (var d : issueData) {
            var ref = new IssueRef(d.issueNumber(), d.issueRepo());
            stateMap.put(ref, d.state());
            titleMap.put(ref, d.title() != null ? d.title() : "");
        }

        var allEdges = new ArrayList<DependencyEdge>();
        for (var d : issueData) {
            allEdges.addAll(DependencyParser.parseEdges(d.issueNumber(), d.issueRepo(), d.body()));
        }

        var blockedByMap = new LinkedHashMap<IssueRef, List<IssueRef>>();
        var blockingMap = new LinkedHashMap<IssueRef, List<IssueRef>>();
        for (var edge : allEdges) {
            blockedByMap.computeIfAbsent(edge.blocked(), k -> new ArrayList<>()).add(edge.blocker());
            blockingMap.computeIfAbsent(edge.blocker(), k -> new ArrayList<>()).add(edge.blocked());
        }

        var nodes = new ArrayList<DependencyNode>();
        for (var d : issueData) {
            if (!"OPEN".equals(d.state())) continue;
            var ref = new IssueRef(d.issueNumber(), d.issueRepo());
            var blockers = blockedByMap.getOrDefault(ref, List.of());
            var blocking = blockingMap.getOrDefault(ref, List.of());

            IssueStatus status;
            if (blockers.isEmpty()) {
                status = IssueStatus.CLEAR;
            } else {
                boolean allResolved = blockers.stream()
                    .allMatch(b -> "CLOSED".equals(stateMap.getOrDefault(b, "EXTERNAL")));
                status = allResolved ? IssueStatus.UNBLOCKED : IssueStatus.BLOCKED;
            }
            nodes.add(new DependencyNode(ref, titleMap.getOrDefault(ref, ""),
                d.state(), status, List.copyOf(blockers), List.copyOf(blocking)));
        }

        var grouped = nodes.stream().collect(Collectors.groupingBy(
            DependencyNode::status, () -> new EnumMap<>(IssueStatus.class), Collectors.toList()));
        for (var s : IssueStatus.values()) grouped.putIfAbsent(s, List.of());

        var criticalPath = computeCriticalPath(nodes, stateMap);

        var graph = new DependencyGraph(List.copyOf(nodes), List.copyOf(allEdges),
            criticalPath, Map.copyOf(grouped), Map.copyOf(stateMap));
        cachedGraph = graph;
        cachedGeneration = gen;
        return graph;
    }

    public List<DependencyNode> blocked(Path workspaceRoot) {
        var graph = buildGraph(workspaceRoot);
        var nodeMap = graph.nodes().stream()
            .collect(Collectors.toMap(DependencyNode::ref, n -> n));
        return graph.grouped().getOrDefault(IssueStatus.BLOCKED, List.of()).stream()
            .sorted(Comparator.<DependencyNode, Integer>comparing(
                n -> chainDepth(n.ref(), nodeMap, new HashSet<>())).reversed())
            .toList();
    }

    public List<DependencyNode> unblocked(Path workspaceRoot) {
        return buildGraph(workspaceRoot).grouped()
            .getOrDefault(IssueStatus.UNBLOCKED, List.of());
    }

    public List<IssueRef> criticalPath(Path workspaceRoot) {
        return buildGraph(workspaceRoot).criticalPath();
    }

    private int chainDepth(IssueRef ref, Map<IssueRef, DependencyNode> nodeMap, Set<IssueRef> visited) {
        if (!visited.add(ref)) return 0;
        var node = nodeMap.get(ref);
        if (node == null) return 0;
        int max = 0;
        for (var blocker : node.blockedBy()) {
            max = Math.max(max, 1 + chainDepth(blocker, nodeMap, visited));
        }
        return max;
    }

    private List<IssueRef> computeCriticalPath(List<DependencyNode> nodes,
                                               Map<IssueRef, String> stateMap) {
        var blocked = nodes.stream()
            .filter(n -> n.status() == IssueStatus.BLOCKED)
            .collect(Collectors.toMap(DependencyNode::ref, n -> n));
        if (blocked.isEmpty()) return List.of();

        var allRefs = new HashSet<>(blocked.keySet());
        for (var node : blocked.values()) {
            for (var b : node.blockedBy()) {
                if ("OPEN".equals(stateMap.getOrDefault(b, "EXTERNAL"))) allRefs.add(b);
            }
        }

        var longestTo = new HashMap<IssueRef, Integer>();
        var predOn = new HashMap<IssueRef, IssueRef>();
        for (var ref : allRefs) longestTo.put(ref, 0);

        boolean changed = true;
        int iterations = 0;
        while (changed && iterations < allRefs.size()) {
            changed = false;
            iterations++;
            for (var node : blocked.values()) {
                for (var blocker : node.blockedBy()) {
                    if (!"OPEN".equals(stateMap.getOrDefault(blocker, "EXTERNAL"))) continue;
                    int newDist = longestTo.getOrDefault(blocker, 0) + 1;
                    if (newDist > longestTo.getOrDefault(node.ref(), 0)) {
                        longestTo.put(node.ref(), newDist);
                        predOn.put(node.ref(), blocker);
                        changed = true;
                    }
                }
            }
        }

        var deepest = longestTo.entrySet().stream()
            .max(Comparator.comparingInt(Map.Entry::getValue))
            .map(Map.Entry::getKey).orElse(null);
        if (deepest == null || longestTo.get(deepest) == 0) return List.of();

        var path = new ArrayList<IssueRef>();
        var current = deepest;
        var visited = new HashSet<IssueRef>();
        while (current != null && visited.add(current)) {
            path.add(current);
            current = predOn.get(current);
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }

    static String extractOwnerRepo(String remoteUrl) {
        if (remoteUrl == null) return null;
        Matcher m = SSH_URL.matcher(remoteUrl);
        if (m.find()) return m.group(1);
        m = HTTPS_URL.matcher(remoteUrl);
        if (m.find()) return m.group(1);
        return null;
    }
}
