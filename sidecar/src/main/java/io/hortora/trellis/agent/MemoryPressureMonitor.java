package io.hortora.trellis.agent;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class MemoryPressureMonitor {

    private static final Logger LOG = Logger.getLogger(MemoryPressureMonitor.class);
    private static final long PER_AGENT_CRITICAL_BYTES = 1_073_741_824L;

    private final ConcurrentHashMap<String, Instant> firstExceeded = new ConcurrentHashMap<>();

    public List<EvictionCandidate> evaluate(List<AgentSnapshot> snapshots) {
        var candidates = new ArrayList<EvictionCandidate>();
        var currentTerminals = new HashSet<String>();
        var runningByRss = new ArrayList<AgentSnapshot>();

        for (var s : snapshots) {
            if (s.process() == null || s.process().state() != AgentState.RUNNING) continue;
            currentTerminals.add(s.terminalName());
            runningByRss.add(s);
            if (s.process().memoryBytes() >= PER_AGENT_CRITICAL_BYTES) {
                var exceeded = firstExceeded.computeIfAbsent(s.terminalName(), k -> Instant.now());
                candidates.add(new EvictionCandidate(
                        s.terminalName(), s.process().memoryBytes(), exceeded,
                        EvictionReason.PER_AGENT_CRITICAL));
            } else {
                firstExceeded.remove(s.terminalName());
            }
        }

        if (systemPressureDetected()) {
            long totalRss = runningByRss.stream().mapToLong(s -> s.process().memoryBytes()).sum();
            runningByRss.sort(Comparator.comparingLong((AgentSnapshot s) -> s.process().memoryBytes()).reversed());
            long target = totalRss / 2;
            long accumulated = 0;
            for (var s : runningByRss) {
                if (accumulated >= target) break;
                if (candidates.stream().noneMatch(c -> c.terminalName().equals(s.terminalName()))) {
                    var exceeded = firstExceeded.computeIfAbsent(s.terminalName(), k -> Instant.now());
                    candidates.add(new EvictionCandidate(
                            s.terminalName(), s.process().memoryBytes(), exceeded,
                            EvictionReason.SYSTEM_PRESSURE));
                }
                accumulated += s.process().memoryBytes();
            }
        }

        firstExceeded.keySet().removeIf(k -> !currentTerminals.contains(k));
        return candidates;
    }

    boolean systemPressureDetected() {
        try {
            var proc = new ProcessBuilder("memory_pressure").start();
            var output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            return output.contains("WARNING") || output.contains("CRITICAL");
        } catch (Exception e) {
            LOG.debugf("memory_pressure check failed: %s", e.getMessage());
            return false;
        }
    }
}
