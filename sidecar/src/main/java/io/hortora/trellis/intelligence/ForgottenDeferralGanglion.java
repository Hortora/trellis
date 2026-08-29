package io.hortora.trellis.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.JavaSwitchGanglion;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;

import java.util.Map;
import java.util.Set;

public class ForgottenDeferralGanglion extends JavaSwitchGanglion {

    static final int STALE_THRESHOLD_DAYS = 14;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ForgottenDeferralGanglion() {
        super("forgotten-deferral", Set.of("trellis.deferred.item"));
    }

    @Override
    @SuppressWarnings("unchecked")
    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
        try {
            var data = MAPPER.readValue(event.getData().toBytes(), Map.class);
            String title = (String) data.getOrDefault("title", "");
            String reason = (String) data.getOrDefault("reason", "");
            String blockerState = (String) data.getOrDefault("blockerState", "");
            int deferredDaysAgo = ((Number) data.getOrDefault("deferredDaysAgo", 0)).intValue();

            boolean blockerResolved = "CLOSED".equals(blockerState);

            if (blockerResolved) {
                return detected(0.7, Map.of(
                        "title", title,
                        "reason", reason,
                        "blockerState", blockerState,
                        "deferredDaysAgo", deferredDaysAgo
                ));
            }

            if (deferredDaysAgo >= STALE_THRESHOLD_DAYS) {
                return detected(0.5, Map.of(
                        "title", title,
                        "reason", reason,
                        "deferredDaysAgo", deferredDaysAgo
                ));
            }

            return noise();
        } catch (Exception e) {
            return noise();
        }
    }
}
