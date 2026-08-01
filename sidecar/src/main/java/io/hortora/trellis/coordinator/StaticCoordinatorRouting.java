package io.hortora.trellis.coordinator;

import io.casehub.blocks.agentic.routing.RoutingContext;
import io.casehub.blocks.agentic.routing.RoutingDecision;
import io.casehub.blocks.agentic.routing.RoutingStrategy;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class StaticCoordinatorRouting implements RoutingStrategy<CoordinatorTask> {

    @Override
    public Uni<RoutingDecision> route(RoutingContext<CoordinatorTask> context) {
        return Uni.createFrom().item(new RoutingDecision.Selected(List.of(), "static-l1"));
    }
}
