package io.hortora.trellis.terminal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TerminalRegistryTest {

    static final String PREFIX = "trellis-test-";

    TmuxManager      tmux;
    TerminalRegistry registry;
    String           sessionName;

    @BeforeAll
    static void checkTmux() throws IOException, InterruptedException {
        var p = new ProcessBuilder("tmux", "-V").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue(p.waitFor() == 0, "tmux not available");
    }

    @BeforeEach
    void setUp() {
        tmux = new TmuxManager();
        registry = new TerminalRegistry(tmux);
        sessionName = PREFIX + UUID.randomUUID().toString().substring(0, 6);
    }

    @AfterEach
    void tearDown() throws IOException, InterruptedException {
        if (tmux.hasSession(sessionName)) {
            tmux.killSession(sessionName);
        }
    }

    @Test
    void createSessionAddsToRegistry() throws IOException, InterruptedException {
        registry.createSession(sessionName, "/tmp", null, null, null);

        assertTrue(registry.get(sessionName).isPresent());
        assertTrue(tmux.hasSession(sessionName));
    }

    @Test
    void createSessionSetsMetadataOptions() throws IOException, InterruptedException {
        registry.createSession(sessionName, "/tmp", "3", "engine", "42");

        assertEquals("3", tmux.getOption(sessionName, "@trellis_slot").orElse(null));
        assertEquals("engine", tmux.getOption(sessionName, "@trellis_repo").orElse(null));
        assertEquals("42", tmux.getOption(sessionName, "@trellis_issue").orElse(null));

        var info = registry.get(sessionName).orElseThrow();
        assertEquals("3", info.slot());
        assertEquals("engine", info.repo());
        assertEquals("42", info.issue());
    }

    @Test
    void createSessionRejectsDuplicateNameAtomically() throws IOException, InterruptedException {
        registry.createSession(sessionName, "/tmp", null, null, null);

        assertThrows(IllegalStateException.class, () ->
                                                          registry.createSession(sessionName, "/tmp", null, null, null));

        assertTrue(registry.get(sessionName).isPresent());
        assertTrue(tmux.hasSession(sessionName));
    }


    @Test
    void destroySessionRemovesFromRegistryAndTmux() throws IOException, InterruptedException {
        registry.createSession(sessionName, "/tmp", null, null, null);
        registry.destroySession(sessionName);

        assertTrue(registry.get(sessionName).isEmpty());
        assertFalse(tmux.hasSession(sessionName));
    }

    @Test
    void listReturnsAllRegisteredSessions() throws IOException, InterruptedException {
        registry.createSession(sessionName, "/tmp", null, null, null);

        var sessions = registry.list();

        assertTrue(sessions.stream().anyMatch(s -> s.name().equals(sessionName)));
    }

    @Test
    void bootstrapDiscoversExistingSessions() throws IOException, InterruptedException {
        tmux.createSession(sessionName, "/tmp");
        tmux.setOption(sessionName, "@trellis_slot", "7");
        tmux.setOption(sessionName, "@trellis_repo", "blocks");

        registry.bootstrap(PREFIX);

        var info = registry.get(sessionName).orElseThrow();
        assertEquals("7", info.slot());
        assertEquals("blocks", info.repo());
    }

    @Test
    void bootstrapIgnoresNonMatchingSessions() throws IOException, InterruptedException {
        String otherSession = "other-" + UUID.randomUUID().toString().substring(0, 6);
        try {
            tmux.createSession(otherSession, "/tmp");

            registry.bootstrap(PREFIX);

            assertTrue(registry.get(otherSession).isEmpty());
        } finally {
            if (tmux.hasSession(otherSession)) tmux.killSession(otherSession);
        }
    }

    @Test
    void getReturnsEmptyForUnknownSession() {
        assertTrue(registry.get("trellis-nonexistent").isEmpty());
    }
}
