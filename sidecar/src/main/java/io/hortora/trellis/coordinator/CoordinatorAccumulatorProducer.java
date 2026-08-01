package io.hortora.trellis.coordinator;

import io.casehub.blocks.summarisation.EventAccumulator;
import io.casehub.blocks.summarisation.WindowPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

@ApplicationScoped
public class CoordinatorAccumulatorProducer {

    @Inject
    CoordinatorConfig config;

    @Produces
    @ApplicationScoped
    EventAccumulator<CoordinatorEvent> coordinatorAccumulator() {
        return new EventAccumulator<>(new WindowPolicy(config.windowTimeMs(), config.windowCount()));
    }
}
