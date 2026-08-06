package io.hortora.trellis.agent;

import io.casehub.pages.push.EventBroadcaster;
import io.hortora.trellis.mcp.GenerationCounter;
import io.hortora.trellis.terminal.TerminalInfo;
import io.hortora.trellis.terminal.TmuxManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class AgentProcessManager {

    private static final Logger LOG = Logger.getLogger(AgentProcessManager.class);
    static final Set<String> SHELL_COMMANDS = Set.of("bash", "zsh", "sh", "dash", "fish");
    private static final long STARTING_TIMEOUT_MS = 15_000;

    private final TmuxManager tmux;
    private final EventBroadcaster broadcaster;
    private final GenerationCounter generation;
    private final ConcurrentHashMap<String, AgentProcess> agents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastErrors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> startingTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Inject
    public AgentProcessManager(TmuxManager tmux, EventBroadcaster broadcaster, GenerationCounter generation) {
        this.tmux = tmux;
        this.broadcaster = broadcaster;
        this.generation = generation;
    }

    public void initializeFromBootstrap(List<TerminalInfo> terminals) {
        for (var terminal : terminals) {
            try {
                var paused = tmux.getOption(terminal.name(), "@trellis_agent_state");
                if (paused.isPresent()) {
                    if ("PAUSED_BY_COORDINATOR".equals(paused.get())) {
                        agents.put(terminal.name(), AgentProcess.pausedByCoordinator(null));
                    } else if ("PAUSED".equals(paused.get())) {
                        agents.put(terminal.name(), AgentProcess.paused(null));
                    }
                }
            } catch (IOException | InterruptedException e) {
                LOG.debugf("Could not read agent state for %s: %s", terminal.name(), e.getMessage());
            }
        }
    }

    public void pollTerminal(TerminalInfo terminal) {
        var lock = locks.computeIfAbsent(terminal.name(), k -> new ReentrantLock());
        if (!lock.tryLock()) return;
        try {
            doPoll(terminal);
        } finally {
            lock.unlock();
        }
    }

    void pollTerminalWithPsOutput(TerminalInfo terminal, String psOutput) {
        try {
            var currentCommand = tmux.displayMessage(terminal.name(), "#{pane_current_command}").trim();
            var panePidStr = tmux.displayMessage(terminal.name(), "#{pane_pid}").trim();
            long panePid = Long.parseLong(panePidStr);
            processDiscovery(terminal.name(), currentCommand, panePid, psOutput);
        } catch (Exception e) {
            LOG.debugf("Poll failed for %s: %s", terminal.name(), e.getMessage());
        }
    }

    private void doPoll(TerminalInfo terminal) {
        try {
            var currentCommand = tmux.displayMessage(terminal.name(), "#{pane_current_command}").trim();
            var panePidStr = tmux.displayMessage(terminal.name(), "#{pane_pid}").trim();
            long panePid = Long.parseLong(panePidStr);
            processDiscovery(terminal.name(), currentCommand, panePid, null);
        } catch (Exception e) {
            LOG.debugf("Poll failed for %s: %s", terminal.name(), e.getMessage());
        }
    }

    private void processDiscovery(String name, String currentCommand, long panePid, String psOutput) {
        var existing = agents.get(name);
        var existingState = existing != null ? existing.state() : null;

        if (existingState == AgentState.PAUSED || existingState == AgentState.PAUSED_BY_COORDINATOR) return;

        if (existingState == AgentState.STARTING) {
            var startTime = startingTimestamps.get(name);
            if (startTime != null && System.currentTimeMillis() - startTime < STARTING_TIMEOUT_MS) {
                if (SHELL_COMMANDS.contains(currentCommand)) return;
            } else if (startTime != null) {
                agents.remove(name);
                startingTimestamps.remove(name);
                lastErrors.put(name, "Start timeout: no process appeared within 15s");
                broadcastState(name);
                return;
            }
        }

        if (SHELL_COMMANDS.contains(currentCommand)) {
            if (existingState == AgentState.RUNNING) {
                agents.remove(name);
                lastErrors.put(name, "Agent process ended — check terminal for details");
                broadcastState(name);
            }
            return;
        }

        Optional<ProcessTreeWalker.ProcessTree> tree;
        try {
            tree = psOutput != null
                    ? ProcessTreeWalker.fromPsOutput(psOutput, panePid)
                    : ProcessTreeWalker.walk(panePid);
        } catch (Exception e) {
            LOG.debugf("Tree walk failed for %s: %s", name, e.getMessage());
            return;
        }

        if (tree.isEmpty()) return;

        var pt = tree.get();
        var process = new AgentProcess(pt.claudePid(), AgentState.RUNNING,
                pt.totalRssBytes(), existing != null ? existing.startedAt() : Instant.now(),
                existing != null ? existing.command() : "claude");
        agents.put(name, process);
        startingTimestamps.remove(name);
        lastErrors.remove(name);
        broadcastState(name);
    }


    public void startAgent(String terminalName, StartAgentRequest request)
            throws IOException, InterruptedException {
        verifyShellForeground(terminalName);
        String command = buildCommand(request);
        setStarting(terminalName, command);
        tmux.sendKeys(terminalName, command + "\n");
    }

    public void stopAgent(String terminalName) throws IOException, InterruptedException {
        var existing = agents.get(terminalName);
        if (existing == null) {return;}
        if (existing.state() == AgentState.PAUSED) {
            tmux.setOption(terminalName, "@trellis_agent_state", "");
            clearState(terminalName);
            broadcastState(terminalName);
            return;
        }
        if (existing.pid() > 0) {
            treeKill(terminalName, existing.pid());
        }
        clearState(terminalName);
        broadcastState(terminalName);
    }

    public void pauseAgent(String terminalName) throws IOException, InterruptedException {
        var existing = agents.get(terminalName);
        if (existing == null || existing.state() != AgentState.RUNNING) {
            throw new IllegalStateException("Cannot pause agent in state: " +
                                            (existing != null ? existing.state() : "IDLE"));
        }
        tmux.setOption(terminalName, "@trellis_agent_state", "PAUSED");
        agents.put(terminalName, AgentProcess.paused(existing.command()));
        broadcastState(terminalName);
        if (existing.pid() > 0) {
            treeKill(terminalName, existing.pid());
        }
    }

    public void resumeAgent(String terminalName) throws IOException, InterruptedException {
        var existing = agents.get(terminalName);
        if (existing == null || (existing.state() != AgentState.PAUSED
                                 && existing.state() != AgentState.PAUSED_BY_COORDINATOR)) {
            throw new IllegalStateException("Cannot resume agent in state: " +
                                            (existing != null ? existing.state() : "IDLE"));
        }
        tmux.setOption(terminalName, "@trellis_agent_state", "");
        verifyShellForeground(terminalName);
        setStarting(terminalName, "claude -c");
        tmux.sendKeys(terminalName, "claude -c\n");
    }

    public void refreshAgent(String terminalName) throws IOException, InterruptedException {
        var existing = agents.get(terminalName);
        if (existing == null || existing.state() != AgentState.RUNNING) {
            throw new IllegalStateException("Cannot refresh agent in state: " +
                                            (existing != null ? existing.state() : "IDLE"));
        }
        setStarting(terminalName, "claude -c");
        if (existing.pid() > 0) {
            treeKill(terminalName, existing.pid());
        }
        try {Thread.sleep(500);} catch (InterruptedException ignored) {}
        tmux.sendKeys(terminalName, "claude -c\n");
    }

    public void gracefulShutdown(String terminalName) throws IOException, InterruptedException {
        var lock = lockFor(terminalName);
        lock.lock();
        try {
            var existing = agents.get(terminalName);
            if (existing == null) {return;}

            var state = existing.state();
            if (state != AgentState.RUNNING && state != AgentState.STARTING) {return;}

            if (state == AgentState.STARTING) {
                if (existing.pid() > 0) {treeKill(terminalName, existing.pid());}
                markPausedByCoordinator(terminalName, existing.command());
                return;
            }

            tmux.sendKeys(terminalName, "Escape");
            Thread.sleep(500);

            var cmd = tmux.displayMessage(terminalName, "#{pane_current_command}").trim();
            if (!SHELL_COMMANDS.contains(cmd)) {
                tmux.sendKeys(terminalName, "/exit\n");
                long deadline = System.currentTimeMillis() + 10_000;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(500);
                    cmd = tmux.displayMessage(terminalName, "#{pane_current_command}").trim();
                    if (SHELL_COMMANDS.contains(cmd)) {break;}
                }
                if (!SHELL_COMMANDS.contains(cmd) && existing.pid() > 0) {
                    treeKill(terminalName, existing.pid());
                }
            }

            markPausedByCoordinator(terminalName, existing.command());
        } finally {
            lock.unlock();
        }
    }

    private void markPausedByCoordinator(String terminalName, String command)
            throws IOException, InterruptedException {
        tmux.setOption(terminalName, "@trellis_agent_state", "PAUSED_BY_COORDINATOR");
        agents.put(terminalName, AgentProcess.pausedByCoordinator(command));
        startingTimestamps.remove(terminalName);
        lastErrors.remove(terminalName);
        broadcastState(terminalName);
    }


    private void verifyShellForeground(String terminalName) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 5; attempt++) {
            var cmd = tmux.displayMessage(terminalName, "#{pane_current_command}").trim();
            if (SHELL_COMMANDS.contains(cmd)) return;
            if (cmd.contains("claude") || cmd.matches("\\d+\\.\\d+\\.\\d+")) {
                throw new IllegalStateException("Agent is already running in this terminal (foreground: " + cmd + ")");
            }
            if (!cmd.isEmpty()) {
                throw new IllegalStateException("Terminal foreground is '" + cmd + "', not a shell");
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Terminal shell did not start within 1s");
    }

    private String buildCommand(StartAgentRequest request) {
        if (request.resume()) {return "claude -c";}
        if (request.prompt() != null) {
            return "claude -p " + shellEscape(request.prompt());
        }
        return "claude";
    }

    static String shellEscape(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void treeKill(String terminalName, long rootPid) {
        try {
            var treeOpt = ProcessTreeWalker.walk(rootPid);
            var handle  = ProcessHandle.of(rootPid);
            if (handle.isPresent()) {
                handle.get().destroy();
                try {
                    handle.get().onExit().orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join();
                } catch (java.util.concurrent.CompletionException ignored) {}
            }
            if (handle.isPresent() && handle.get().isAlive()) {
                treeOpt.ifPresent(tree -> {
                    var reversed = new ArrayList<>(tree.allPids());
                    java.util.Collections.reverse(reversed);
                    for (long pid : reversed) {
                        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                    }
                });
            }
        } catch (Exception e) {
            LOG.warnf("Tree kill failed for %s (pid=%d): %s", terminalName, rootPid, e.getMessage());
        }
    }

    public AgentSnapshot getSnapshot(String terminalName, TerminalInfo terminal) {
        return new AgentSnapshot(terminalName, terminal, agents.get(terminalName),
                lastErrors.get(terminalName));
    }

    public List<AgentSnapshot> getAllSnapshots(List<TerminalInfo> terminals) {
        return terminals.stream()
                .map(t -> getSnapshot(t.name(), t))
                .toList();
    }

    public void setStarting(String name, String command) {
        agents.put(name, new AgentProcess(0, AgentState.STARTING, 0, Instant.now(), command));
        startingTimestamps.put(name, System.currentTimeMillis());
        lastErrors.remove(name);
        broadcastState(name);
    }

    public void setPaused(String name, String command) {
        agents.put(name, AgentProcess.paused(command));
        broadcastState(name);
    }

    public void clearState(String name) {
        agents.remove(name);
        lastErrors.remove(name);
        startingTimestamps.remove(name);
    }

    public ReentrantLock lockFor(String terminalName) {
        return locks.computeIfAbsent(terminalName, k -> new ReentrantLock());
    }

    private void broadcastState(String name) {
        generation.increment();
        try {
            var process = agents.get(name);
            broadcaster.broadcast("agent:state",
                    Map.of("terminalName", name,
                           "state", process != null ? process.state().name() : "IDLE",
                           "pid", process != null ? process.pid() : 0,
                           "memoryBytes", process != null ? process.memoryBytes() : 0));
        } catch (Exception e) {
            LOG.debugf("Failed to broadcast agent state for %s: %s", name, e.getMessage());
        }
    }
}
