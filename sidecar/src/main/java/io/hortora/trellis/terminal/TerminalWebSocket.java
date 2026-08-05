package io.hortora.trellis.terminal;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

@WebSocket(path = "/ws/terminal/{id}/{cols}/{rows}")
public class TerminalWebSocket {

    private static final Logger LOG = Logger.getLogger(TerminalWebSocket.class);

    @Inject
    TmuxManager tmux;

    @Inject
    TerminalRegistry registry;

    private final ConcurrentHashMap<String, String> sessionNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> fifoPaths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WebSocketConnection> activeBySession = new ConcurrentHashMap<>();

    {
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            for (var path : fifoPaths.values()) {
                try {java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(path));} catch (Exception ignored) {}
            }
        }));
    }


    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event) {
        try {
            java.nio.file.Path tmpDir = java.nio.file.Path.of("/tmp");
            if (java.nio.file.Files.isDirectory(tmpDir)) {
                try (var stream = java.nio.file.Files.newDirectoryStream(tmpDir, "trellis-*.pipe")) {
                    for (var fifo : stream) {
                        java.nio.file.Files.deleteIfExists(fifo);
                        LOG.infof("Startup sweep: removed stale FIFO %s", fifo);
                    }
                }
            }
        } catch (IOException e) {
            LOG.warnf("Failed to sweep stale FIFOs: %s", e.getMessage());
        }
    }

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        var sessionName = connection.pathParam("id");
        if (registry.get(sessionName).isEmpty()) {
            LOG.warnf("WebSocket open for unknown session %s — closing", sessionName);
            try {connection.closeAndAwait();} catch (Exception ignored) {}
            return;
        }

        int cols     = parsePathInt(connection.pathParam("cols"));
        int rows     = parsePathInt(connection.pathParam("rows"));
        var fifoPath = "/tmp/trellis-" + connection.id() + ".pipe";

        sessionNames.put(connection.id(), sessionName);

        var previous = activeBySession.put(sessionName, connection);
        if (previous != null && !previous.id().equals(connection.id())) {
            LOG.infof("Session takeover for %s — closing previous connection %s", sessionName, previous.id());
            cleanup(previous);
            try {previous.closeAndAwait(new io.quarkus.websockets.next.CloseReason(4001, "session-takeover"));} catch (Exception ignored) {}
        }

        try {
            new ProcessBuilder("mkfifo", fifoPath)
                    .redirectErrorStream(true).start().waitFor();
            fifoPaths.put(connection.id(), fifoPath);

            Thread.ofVirtual().name("trellis-fifo-" + sessionName).start(() -> {
                try {
                    new FifoRelay(
                            new FileInputStream(fifoPath),
                            text -> connection.sendTextAndAwait(text)
                    ).relay();
                } catch (IOException e) {
                    LOG.debugf("FIFO stream ended for session %s: %s", sessionName, e.getMessage());
                }
            });

            tmux.pipePaneToFifo(sessionName, fifoPath);

            if (cols > 0 && rows > 0) {
                tmux.forceRedraw(sessionName, cols, rows);
            }

        } catch (IOException | InterruptedException e) {
            LOG.errorf("Failed to set up pipe for session %s: %s", sessionName, e.getMessage());
            cleanup(connection);
            try {connection.closeAndAwait();} catch (Exception ignored) {}
        }
    }

    @OnTextMessage
    public void onMessage(WebSocketConnection connection, String message) {
        var sessionName = sessionNames.get(connection.id());
        if (sessionName == null) return;
        try {
            tmux.sendKeys(sessionName, message);
        } catch (IOException | InterruptedException e) {
            LOG.debugf("Failed to send input to session %s: %s", sessionName, e.getMessage());
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        cleanup(connection);
    }

    @OnError
    public void onError(Throwable error, WebSocketConnection connection) {
        LOG.warnf("WebSocket error for connection %s: %s", connection.id(), error.getMessage());
        cleanup(connection);
    }

    private void cleanup(WebSocketConnection connection) {
        var sessionName = sessionNames.remove(connection.id());
        if (sessionName != null) {
            try {tmux.stopPipePane(sessionName);} catch (Exception ignored) {}
            activeBySession.remove(sessionName, connection);
        }
        var fifoPath = fifoPaths.remove(connection.id());
        if (fifoPath != null) {
            try {java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(fifoPath));} catch (Exception ignored) {}
        }
    }

    private static int parsePathInt(String value) {
        try { return value != null ? Integer.parseInt(value) : 0; }
        catch (NumberFormatException e) { return 0; }
    }
}
