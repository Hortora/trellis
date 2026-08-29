package io.hortora.trellis.intelligence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public final class TrellisCloudEvents {

    static final URI SOURCE = URI.create("trellis-intelligence");
    static final String TENANCY_ID = "trellis";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TrellisCloudEvents() {}

    public static CloudEvent worklogSnapshot(Map<String, Object> data) {
        return build("trellis.worklog.snapshot", data);
    }

    public static CloudEvent enrichmentIssue(Map<String, Object> data) {
        return build("trellis.enrichment.issue", data);
    }

    public static CloudEvent deferredItem(Map<String, Object> data) {
        return build("trellis.deferred.item", data);
    }

    public static CloudEvent crossRepoChange(Map<String, Object> data) {
        return build("trellis.crossrepo.change", data);
    }

    private static CloudEvent build(String type, Map<String, Object> data) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(data);
            return CloudEventBuilder.v1()
                    .withId(UUID.randomUUID().toString())
                    .withSource(SOURCE)
                    .withType(type)
                    .withTime(OffsetDateTime.now())
                    .withExtension("tenancyid", TENANCY_ID)
                    .withData("application/json", json)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException("Failed to build CloudEvent", e);
        }
    }
}
