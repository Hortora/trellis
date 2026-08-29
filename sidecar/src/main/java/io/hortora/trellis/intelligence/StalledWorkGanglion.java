package io.hortora.trellis.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.JavaSwitchGanglion;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class StalledWorkGanglion extends JavaSwitchGanglion {

    static final int ATTENTION_THRESHOLD_DAYS = 7;
    static final int ACTION_THRESHOLD_DAYS = 14;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public StalledWorkGanglion() {
        super("stalled-work", Set.of("trellis.worklog.snapshot"));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
        try {
            var data = MAPPER.readValue(event.getData().toBytes(), Map.class);
            int daysAgo = ((Number) data.getOrDefault("lastEventDaysAgo", 0)).intValue();
            String state = (String) data.getOrDefault("state", "");

            if (!"active".equals(state) || daysAgo < ATTENTION_THRESHOLD_DAYS) {
                return noise();
            }

            double confidence;
            if (daysAgo >= ACTION_THRESHOLD_DAYS) {
                confidence = 0.85;
            } else {
                confidence = 0.45 + (daysAgo - ATTENTION_THRESHOLD_DAYS) * 0.05;
            }
            confidence = Math.min(confidence, 1.0);

            return detected(confidence, Map.of(
                    "branch", data.getOrDefault("branch", ""),
                    "issueNumber", data.getOrDefault("issueNumber", 0),
                    "lastEventDaysAgo", daysAgo,
                    "state", state
            ));
        } catch (Exception e) {
            return noise();
        }
    }
}
