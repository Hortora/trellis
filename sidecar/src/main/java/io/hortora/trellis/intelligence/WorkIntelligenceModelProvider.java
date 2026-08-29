package io.hortora.trellis.intelligence;

import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationSource;
import io.hortora.trellis.mcp.ActionDescriptor;
import io.hortora.trellis.mcp.ModelProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class WorkIntelligenceModelProvider implements ModelProvider {

    private final SituationSource situationSource;

    @Inject
    public WorkIntelligenceModelProvider(SituationSource situationSource) {
        this.situationSource = situationSource;
    }

    @Override
    public String domain() {
        return "intelligence";
    }

    @Override
    public Object summary() {
        var situations = situationSource.activeSituations(TrellisCloudEvents.TENANCY_ID);
        var findings = new ArrayList<Map<String, Object>>();
        int actionNeeded = 0, attention = 0, info = 0;

        for (ActiveSituation situation : situations) {
            String severity = mapSeverity(situation.confidence());
            switch (severity) {
                case "ACTION_NEEDED" -> actionNeeded++;
                case "ATTENTION" -> attention++;
                case "INFO" -> info++;
            }

            var finding = new LinkedHashMap<String, Object>();
            finding.put("facet", situation.situationId());
            finding.put("severity", severity);
            finding.put("subject", situation.correlationKey());
            finding.put("summary", buildSummary(situation));
            finding.put("suggestion", buildSuggestion(situation));
            finding.put("confidence", situation.confidence());
            finding.put("since", situation.since().toString());
            finding.put("lastSignal", situation.lastSignal().toString());
            finding.put("evidence", situation.evidence());
            findings.add(finding);
        }

        findings.sort((a, b) -> {
            int oa = severityOrdinal((String) a.get("severity"));
            int ob = severityOrdinal((String) b.get("severity"));
            return Integer.compare(ob, oa);
        });

        return Map.of(
                "summary", Map.of("actionNeeded", actionNeeded, "attention", attention, "info", info),
                "findings", findings
        );
    }

    @Override
    public Object resolve(String subpath) {
        return summary();
    }

    @Override
    public List<ActionDescriptor> actionsFor(String nodeType) {
        return List.of();
    }


    static String buildSummary(ActiveSituation situation) {
        var evidence = situation.evidence();
        return switch (situation.situationId()) {
            case "stalled-work" -> {
                int days = ((Number) evidence.getOrDefault("lastEventDaysAgo", 0)).intValue();
                yield "Branch " + evidence.getOrDefault("branch", "?") + " idle for " + days + " days.";
            }
            case "unblocked-work" -> {
                var blockers = evidence.getOrDefault("resolvedBlockers", List.of());
                yield "All blockers resolved (" + blockers + "). Issue ready to resume.";
            }
            case "forgotten-deferral" -> {
                String reason = (String) evidence.getOrDefault("reason", "");
                int    days   = ((Number) evidence.getOrDefault("deferredDaysAgo", 0)).intValue();
                if ("CLOSED".equals(evidence.get("blockerState"))) {
                    yield "Blocker resolved. Deferred " + days + " days ago: " + reason;
                }
                yield "Deferred " + days + " days ago without re-evaluation: " + reason;
            }
            case "cross-repo-dependency" -> {
                String repo = (String) evidence.getOrDefault("upstreamRepo", "?");
                int    pr   = ((Number) evidence.getOrDefault("prNumber", 0)).intValue();
                yield repo + "#" + pr + " merged but not consumed downstream.";
            }
            default -> situation.situationId() + " detected.";
        };
    }

    static String buildSuggestion(ActiveSituation situation) {
        return switch (situation.situationId()) {
            case "stalled-work" -> "Consider resuming or closing issue #" + situation.evidence().getOrDefault("issueNumber", "?") + ".";
            case "unblocked-work" -> "Consider picking up issue #" + situation.evidence().getOrDefault("issueNumber", "?") + ".";
            case "forgotten-deferral" -> "Re-evaluate whether \"" + situation.evidence().getOrDefault("title", "?") + "\" should be promoted.";
            case "cross-repo-dependency" -> "Update " + situation.evidence().getOrDefault("downstreamRepo", "?") + " to consume the upstream change.";
            default -> "";
        };
    }

    static String mapSeverity(double confidence) {
        if (confidence > 0.7) return "ACTION_NEEDED";
        if (confidence >= 0.4) return "ATTENTION";
        return "INFO";
    }

    private static int severityOrdinal(String severity) {
        return switch (severity) {
            case "ACTION_NEEDED" -> 2;
            case "ATTENTION" -> 1;
            default -> 0;
        };
    }
}
