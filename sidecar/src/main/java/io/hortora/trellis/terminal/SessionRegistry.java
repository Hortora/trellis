package io.hortora.trellis.terminal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SessionRegistry {

    private static final Logger LOG = Logger.getLogger(SessionRegistry.class);

    private final TmuxManager tmux;
    private final ConcurrentHashMap<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    @Inject
    public SessionRegistry(TmuxManager tmux) {
        this.tmux = tmux;
    }

    public void createSession(String name, String workingDir, String slot, String repo, String issue)
            throws IOException, InterruptedException {
        tmux.createSession(name, workingDir);

        if (slot != null) tmux.setOption(name, "@trellis_slot", slot);
        if (repo != null) tmux.setOption(name, "@trellis_repo", repo);
        if (issue != null) tmux.setOption(name, "@trellis_issue", issue);

        sessions.put(name, new SessionInfo(name, workingDir, slot, repo, issue));
    }

    public void destroySession(String name) throws IOException, InterruptedException {
        tmux.killSession(name);
        sessions.remove(name);
    }

    public void sendKeys(String name, String text) throws IOException, InterruptedException {
        tmux.sendKeys(name, text);
    }


    public Optional<SessionInfo> get(String name) {
        return Optional.ofNullable(sessions.get(name));
    }

    public List<SessionInfo> list() {
        return List.copyOf(sessions.values());
    }

    public void bootstrap(String prefix) {
        try {
            var names = tmux.listSessions(prefix);
            for (String name : names) {
                String slot = tmux.getOption(name, "@trellis_slot").orElse(null);
                String repo = tmux.getOption(name, "@trellis_repo").orElse(null);
                String issue = tmux.getOption(name, "@trellis_issue").orElse(null);
                sessions.put(name, new SessionInfo(name, null, slot, repo, issue));
                LOG.infof("Bootstrapped session: %s (slot=%s, repo=%s, issue=%s)", name, slot, repo, issue);
            }
        } catch (IOException | InterruptedException e) {
            LOG.warnf(e, "Failed to bootstrap sessions with prefix %s", prefix);
        }
    }
}
