package io.hortora.trellis.intelligence;

import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationDefinitionProvider;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerMode;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class IntelligenceSituationProvider implements SituationDefinitionProvider {

    @Override
    public List<SituationRegistration> registrations() {
        var list = new ArrayList<SituationRegistration>();
        list.add(stalledWork());
        list.add(unblockedWork());
        list.add(forgottenDeferral());
        list.add(crossRepoDependency());
        return list;
    }

    private SituationRegistration stalledWork() {
        return new SituationRegistration(new SituationDefinition(
                "stalled-work",
                Set.of("trellis.worklog.snapshot"),
                null,
                null,
                new ChainMode.Count("stalled-work", 1),
                new TriggerAction.NotifyOnly(),
                new TriggerMode.Repeating(Duration.ofMinutes(5))
        ));
    }

    private SituationRegistration unblockedWork() {
        return new SituationRegistration(new SituationDefinition(
                "unblocked-work",
                Set.of("trellis.enrichment.issue"),
                null,
                null,
                new ChainMode.Count("unblocked-work", 1),
                new TriggerAction.NotifyOnly(),
                new TriggerMode.Repeating(Duration.ofMinutes(5))
        ));
    }

    private SituationRegistration forgottenDeferral() {
        return new SituationRegistration(new SituationDefinition(
                "forgotten-deferral",
                Set.of("trellis.deferred.item"),
                null,
                null,
                new ChainMode.Count("forgotten-deferral", 1),
                new TriggerAction.NotifyOnly(),
                new TriggerMode.Repeating(Duration.ofMinutes(5))
        ));
    }

    private SituationRegistration crossRepoDependency() {
        return new SituationRegistration(new SituationDefinition(
                "cross-repo-dependency",
                Set.of("trellis.crossrepo.change"),
                null,
                null,
                new ChainMode.Count("cross-repo-dependency", 1),
                new TriggerAction.NotifyOnly(),
                new TriggerMode.Repeating(Duration.ofMinutes(5))
        ));
    }


}
