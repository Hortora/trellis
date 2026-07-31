package io.hortora.trellis.issues;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@ApplicationScoped
public class EpicAnalyzer {

    private final DependencyParser parser;
    private final RecommendationEngine recEngine;

    @Inject
    public EpicAnalyzer(DependencyParser parser, RecommendationEngine recEngine) {
        this.parser = parser;
        this.recEngine = recEngine;
    }

    public EpicAnalysis analyze(String owner, String repo, int epicNumber,
                                List<IssueInfo> allIssues) {
        var epicIssue = allIssues.stream()
                .filter(i -> i.owner().equals(owner) && i.repo().equals(repo)
                        && i.number() == epicNumber)
                .findFirst().orElseThrow(() -> new NoSuchElementException(
                        "Epic #" + epicNumber + " not found"));

        var childKeys = new LinkedHashSet<>(
                EpicBodyParser.parseChildren(epicIssue.body(), owner, repo));

        var allDeps = new ArrayList<Dependency>();
        for (var issue : allIssues) allDeps.addAll(parser.parse(issue));

        var externalBlockers = new HashSet<String>();
        for (var dep : allDeps) {
            if (childKeys.contains(dep.fromKey()) && !childKeys.contains(dep.toKey())) {
                externalBlockers.add(dep.toKey());
            }
        }

        var graphNodes = new HashSet<>(childKeys);
        graphNodes.addAll(externalBlockers);

        var graphEdges = allDeps.stream()
                .filter(d -> graphNodes.contains(d.fromKey()) && graphNodes.contains(d.toKey()))
                .toList();

        var closedKeys = allIssues.stream()
                .filter(i -> "CLOSED".equals(i.state()))
                .map(IssueInfo::key)
                .collect(Collectors.toSet());

        var graph = new DependencyGraph(graphNodes, graphEdges,
                closedKeys.stream().filter(graphNodes::contains).collect(Collectors.toSet()));

        var dagNodes = graph.dagLayout();
        var enrichedNodes = dagNodes.stream()
                .map(n -> new DagNode(n.key(), n.layer(), n.index(), n.closed(),
                        n.onCriticalPath(), n.inCycle(),
                        externalBlockers.contains(n.key())))
                .toList();

        var edges = graphEdges.stream()
                .map(d -> new DagEdge(d.toKey(), d.fromKey()))
                .toList();

        var graphData = new GraphData(enrichedNodes, edges);

        var critPathFull = graph.criticalPathFull().stream()
                .filter(childKeys::contains).toList();
        var critPathOpen = graph.criticalPath().stream()
                .filter(childKeys::contains).toList();
        int childTotal = (int) childKeys.stream()
                .filter(k -> allIssues.stream().anyMatch(i -> i.key().equals(k)))
                .count();
        int childClosed = (int) childKeys.stream().filter(closedKeys::contains).count();
        int childOpen = childTotal - childClosed;
        int bottleneckCount = (int) graph.bottlenecks().stream()
                .filter(childKeys::contains).count();
        int maxPar = (int) graph.unblocked().stream()
                .filter(childKeys::contains).count();

        var kpis = new EpicKpis(childTotal, childOpen, childClosed,
                critPathFull.size(), critPathOpen.size(),
                bottleneckCount, maxPar);

        var graphIssues = allIssues.stream()
                .filter(i -> graphNodes.contains(i.key()))
                .toList();

        var recs = recEngine.recommend(graph, graphIssues, childKeys);
        var batches = EpicBodyParser.parseBatches(epicIssue.body(), owner, repo, allIssues);
        var cycles = new ArrayList<>(graph.cycleNodes().stream()
                .filter(childKeys::contains).toList());

        return new EpicAnalysis(graphIssues, graphData, kpis, recs, batches, cycles);
    }

    public EpicSummary summarize(EpicAnalysis analysis, String issueKey, String title) {
        return new EpicSummary(
                issueKey, title,
                analysis.kpis().criticalPathLength(),
                analysis.kpis().bottleneckCount(),
                analysis.recommendations().isEmpty() ? null : analysis.recommendations().getFirst(),
                new Progress(analysis.kpis().total(), analysis.kpis().open(), analysis.kpis().closed())
        );
    }
}
