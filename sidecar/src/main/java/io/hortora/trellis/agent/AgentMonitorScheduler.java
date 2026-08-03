package io.hortora.trellis.agent;

import io.hortora.trellis.terminal.TerminalRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class AgentMonitorScheduler {

    @Inject
    TerminalRegistry                       registry;
    @Inject
    AgentProcessManager                    processManager;
    @Inject
    MemoryPressureMonitor                  memoryMonitor;
    @Inject
    io.casehub.pages.push.EventBroadcaster broadcaster;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("agent-monitor").unstarted(r));

    void onStart(@Observes StartupEvent event) {
        executor.scheduleAtFixedRate(this::poll, 5, 5, TimeUnit.SECONDS);
    }

    void poll() {
        var terminals = registry.list();
        for (var terminal : terminals) {
            processManager.pollTerminal(terminal);
        }
        var snapshots  = processManager.getAllSnapshots(terminals);
        var candidates = memoryMonitor.evaluate(snapshots);
        try {
            broadcaster.broadcast("agent:eviction",
                                  java.util.Map.of("candidates", candidates));
        } catch (Exception e) {
            // broadcast failure is non-fatal
        }
    }
}
