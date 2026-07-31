package io.hortora.trellis.issues;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class RecommendationEngine {

    public List<Recommendation> recommend(DependencyGraph graph,
                                          List<IssueInfo> issues,
                                          Set<String> childKeys) {
        var unblocked = graph.unblocked();
        var critPath = new HashSet<>(graph.criticalPath());
        var cascadeCounts = graph.cascadeUnlockCounts();
        var titleMap = issues.stream()
                .collect(Collectors.toMap(IssueInfo::key, IssueInfo::title, (a, b) -> a));

        var recs = new ArrayList<Recommendation>();

        for (var key : unblocked) {
            if (!childKeys.contains(key)) continue;

            int cascade = cascadeCounts.getOrDefault(key, 0);
            boolean onCritPath = critPath.contains(key);
            String title = titleMap.getOrDefault(key, key);

            Recommendation.Type type;
            int score;
            String reason;

            if (onCritPath) {
                type = Recommendation.Type.CRITICAL_PATH;
                score = 100 + cascade;
                reason = cascade > 0
                        ? "On critical path, unblocks " + cascade + " issue" + (cascade > 1 ? "s" : "") + " transitively"
                        : "On critical path";
            } else if (cascade > 1) {
                type = Recommendation.Type.BOTTLENECK;
                score = 50 + cascade;
                reason = "Bottleneck — completing this unblocks " + cascade + " issues";
            } else {
                continue;
            }

            recs.add(new Recommendation(key, title, type, score, reason));
        }

        recs.sort(Comparator.comparingInt(Recommendation::score).reversed());
        return recs;
    }
}
