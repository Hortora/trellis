package io.hortora.trellis.terminal;

import io.hortora.trellis.mcp.GenerationCounter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class TerminalRegistry {

    private static final Logger LOG = Logger.getLogger(TerminalRegistry.class);

    private final TmuxManager tmux;
    private final SessionLogger sessionLogger;
    private final GenerationCounter generation;

    private final ConcurrentHashMap<String, TerminalInfo> sessions = new ConcurrentHashMap<>();
    private volatile boolean bootstrapComplete = false;

    @Inject
    public TerminalRegistry(TmuxManager tmux, SessionLogger sessionLogger, GenerationCounter generation) {
        this.tmux = tmux;
        this.sessionLogger = sessionLogger;
        this.generation = generation;
    }

    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event) {
        bootstrap("trellis-");
    }

    public void createSession(String name, String workingDir, String slot, String repo, String issue)
            throws IOException, InterruptedException {
        var placeholder = new TerminalInfo(name, workingDir, slot, repo, issue);
        if (sessions.putIfAbsent(name, placeholder) != null) {
            throw new IllegalStateException("Terminal already exists: " + name);
        }
        try {
            tmux.createSession(name, workingDir);
            if (slot != null) {tmux.setOption(name, "@trellis_slot", slot);}
            if (repo != null) {tmux.setOption(name, "@trellis_repo", repo);}
            if (issue != null) {tmux.setOption(name, "@trellis_issue", issue);}
            generation.increment();
        } catch (IOException | InterruptedException e) {
            sessions.remove(name);
            throw e;
        }
    }

    public void destroySession(String name) throws IOException, InterruptedException {
        tmux.killSession(name);
        sessions.remove(name);
        sessionLogger.delete(name);
        generation.increment();
    }

    public void sendKeys(String name, String text) throws IOException, InterruptedException {
        tmux.sendKeys(name, text);
    }

    public void resize(String name, int cols, int rows) throws IOException, InterruptedException {
        tmux.resizeWindow(name, cols, rows);
    }

    public Optional<TerminalInfo> get(String name) {
        return Optional.ofNullable(sessions.get(name));
    }

    public List<TerminalInfo> list() {
        return List.copyOf(sessions.values());
    }

    public boolean isBootstrapComplete() {
        return bootstrapComplete;
    }

    public void bootstrap(String prefix) {
        try {
            var names = tmux.listSessions(prefix);
            for (String name : names) {
                String slot = tmux.getOption(name, "@trellis_slot").orElse(null);
                String repo = tmux.getOption(name, "@trellis_repo").orElse(null);
                String issue = tmux.getOption(name, "@trellis_issue").orElse(null);
                sessions.put(name, new TerminalInfo(name, null, slot, repo, issue));
                LOG.infof("Bootstrapped session: %s (slot=%s, repo=%s, issue=%s)", name, slot, repo, issue);
            }
        } catch (IOException | InterruptedException e) {
            LOG.warnf(e, "Failed to bootstrap sessions with prefix %s", prefix);
        }
        bootstrapComplete = true;
    }
}
