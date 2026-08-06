package io.hortora.trellis.mcp;

import io.hortora.trellis.agent.AgentProcessManager;
import io.hortora.trellis.terminal.SessionLogger;
import io.hortora.trellis.terminal.TerminalRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;

@ApplicationScoped
public class TerminalModelProvider implements ModelProvider {

    @Inject
    TerminalRegistry registry;

    @Inject
    AgentProcessManager processManager;

    @Inject
    SessionLogger sessionLogger;

    private static final List<ActionDescriptor> TERMINAL_ACTIONS = List.of(
            ActionDescriptor.backend("send-input", "Send text input to terminal", "trellis_terminal", "send-input"),
            ActionDescriptor.backend("read-log", "Read session log", "trellis_terminal", "read-log"),
            ActionDescriptor.backend("start-agent", "Start an agent in this terminal", "trellis_agent", "start"),
            ActionDescriptor.backend("stop-agent", "Stop the running agent", "trellis_agent", "stop"),
            ActionDescriptor.backend("graceful-shutdown-agent", "Gracefully shutdown agent", "trellis_agent", "graceful-shutdown"),
            ActionDescriptor.backend("pause-agent", "Pause the running agent", "trellis_agent", "pause"),
            ActionDescriptor.backend("resume-agent", "Resume a paused agent", "trellis_agent", "resume"),
            ActionDescriptor.backend("refresh-agent", "Refresh agent state", "trellis_agent", "refresh"),
            ActionDescriptor.backend("destroy", "Destroy this terminal", "trellis_terminal", "destroy")
    );

    @Override
    public String domain() {
        return "terminals";
    }

    @Override
    public Object summary() {
        var terminals = registry.list();
        var snapshots = processManager.getAllSnapshots(terminals);
        return snapshots.stream().map(this::toMap).toList();
    }

    @Override
    public Object resolve(String subpath) {
        if (subpath == null || subpath.isEmpty()) return summary();
        var name = subpath.split("/")[0];
        var terminal = registry.get(name);
        if (terminal.isEmpty()) return null;
        var snapshot = processManager.getSnapshot(name, terminal.get());
        return toMap(snapshot);
    }

    @Override
    public List<ActionDescriptor> actionsFor(String nodeType) {
        if ("terminal".equals(nodeType)) return TERMINAL_ACTIONS;
        return List.of();
    }

    private LinkedHashMap<String, Object> toMap(io.hortora.trellis.agent.AgentSnapshot snapshot) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", snapshot.terminalName());
        map.put("workingDir", snapshot.terminal().workingDir());
        map.put("slot", snapshot.terminal().slot());
        map.put("repo", snapshot.terminal().repo());
        map.put("issue", snapshot.terminal().issue());
        map.put("sessionLog", sessionLogger.logPath(snapshot.terminalName()).toString());
        if (snapshot.process() != null) {
            var agent = new LinkedHashMap<String, Object>();
            agent.put("state", snapshot.process().state().name());
            agent.put("pid", snapshot.process().pid());
            agent.put("memoryBytes", snapshot.process().memoryBytes());
            if (snapshot.process().startedAt() != null) {
                agent.put("startedAt", snapshot.process().startedAt().toString());
            }
            agent.put("command", snapshot.process().command());
            map.put("agent", agent);
        }
        if (snapshot.lastError() != null) {
            map.put("lastError", snapshot.lastError());
        }
        map.put("actions", TERMINAL_ACTIONS);
        return map;
    }
}
