package io.hortora.trellis.intelligence;

import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CrossRepoAdapter {

    private final Event<CloudEvent> cloudEventBus;

    @Inject
    public CrossRepoAdapter(Event<CloudEvent> cloudEventBus) {
        this.cloudEventBus = cloudEventBus;
    }

    public void emitCrossRepoChanges(List<Map<String, Object>> changes) {
        for (var change : changes) {
            if (cloudEventBus != null) {
                cloudEventBus.fireAsync(TrellisCloudEvents.crossRepoChange(change));
            }
        }
    }
}
