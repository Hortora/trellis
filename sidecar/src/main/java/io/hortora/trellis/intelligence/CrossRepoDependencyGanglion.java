package io.hortora.trellis.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.JavaSwitchGanglion;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class CrossRepoDependencyGanglion extends JavaSwitchGanglion {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public CrossRepoDependencyGanglion() {
        super("cross-repo-dependency", Set.of("trellis.crossrepo.change"));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
        try {
            var data = MAPPER.readValue(event.getData().toBytes(), Map.class);
            boolean consumed = Boolean.TRUE.equals(data.get("consumed"));
            if (consumed) return noise();

            String upstreamRepo = (String) data.getOrDefault("upstreamRepo", "");
            int prNumber = ((Number) data.getOrDefault("prNumber", 0)).intValue();
            String prTitle = (String) data.getOrDefault("prTitle", "");
            String downstreamRepo = (String) data.getOrDefault("downstreamRepo", "");
            var relatedIssues = (List<?>) data.getOrDefault("relatedIssues", List.of());

            double confidence = relatedIssues.isEmpty() ? 0.5 : 0.8;

            return detected(confidence, Map.of(
                    "upstreamRepo", upstreamRepo,
                    "prNumber", prNumber,
                    "prTitle", prTitle,
                    "downstreamRepo", downstreamRepo,
                    "relatedIssueCount", relatedIssues.size()
            ));
        } catch (Exception e) {
            return noise();
        }
    }
}
