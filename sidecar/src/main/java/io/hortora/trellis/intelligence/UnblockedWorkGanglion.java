package io.hortora.trellis.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.JavaSwitchGanglion;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class UnblockedWorkGanglion extends JavaSwitchGanglion {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public UnblockedWorkGanglion() {
        super("unblocked-work", Set.of("trellis.enrichment.issue"));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
        try {
            var data = MAPPER.readValue(event.getData().toBytes(), Map.class);
            String state = (String) data.getOrDefault("state", "");
            if (!"OPEN".equals(state)) return noise();

            var blockedBy = (List<Map<String, Object>>) data.getOrDefault("blockedBy", List.of());
            if (blockedBy.isEmpty()) return noise();

            boolean allResolved = blockedBy.stream()
                    .allMatch(b -> "CLOSED".equals(b.get("state")));

            if (!allResolved) return noise();

            int issueNumber = ((Number) data.getOrDefault("issueNumber", 0)).intValue();
            var resolvedBlockers = blockedBy.stream()
                    .map(b -> ((Number) b.get("number")).intValue())
                    .toList();

            return detected(0.9, Map.of(
                    "issueNumber", issueNumber,
                    "resolvedBlockers", resolvedBlockers
            ));
        } catch (Exception e) {
            return noise();
        }
    }
}
