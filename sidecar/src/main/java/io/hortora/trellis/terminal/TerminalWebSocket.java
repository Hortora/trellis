package io.hortora.trellis.terminal;

import io.quarkus.websockets.next.*;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

@WebSocket(path = "/ws/terminal/{id}/{cols}/{rows}")
public class TerminalWebSocket {

    private static final Logger LOG = Logger.getLogger(TerminalWebSocket.class);

    @Inject
    TmuxManager tmux;

    @Inject
    SessionRegistry registry;

    private final ConcurrentHashMap<String, String> sessionNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> fifoPaths = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        var sessionName = connection.pathParam("id");
        if (registry.get(sessionName).isEmpty()) {
            LOG.warnf("WebSocket open for unknown session %s — closing", sessionName);
            try { connection.closeAndAwait(); } catch (Exception ignored) {}
            return;
        }

        int cols = parsePathInt(connection.pathParam("cols"));
        int rows = parsePathInt(connection.pathParam("rows"));
        var fifoPath = "/tmp/trellis-" + connection.id() + ".pipe";

        sessionNames.put(connection.id(), sessionName);

        try {
            if (cols > 0 && rows > 0) {
                tmux.resizeWindow(sessionName, cols, rows);
            }

            new ProcessBuilder("mkfifo", fifoPath)
                    .redirectErrorStream(true).start().waitFor();
            fifoPaths.put(connection.id(), fifoPath);

            Thread.ofVirtual().name("trellis-fifo-" + sessionName).start(() -> {
                try (var in = new BufferedInputStream(new FileInputStream(fifoPath))) {
                    var buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        connection.sendTextAndAwait(new String(buf, 0, n));
                    }
                } catch (IOException e) {
                    LOG.debugf("FIFO stream ended for session %s: %s", sessionName, e.getMessage());
                }
            });

            tmux.pipePaneToFifo(sessionName, fifoPath);

        } catch (IOException | InterruptedException e) {
            LOG.errorf("Failed to set up pipe for session %s: %s", sessionName, e.getMessage());
            cleanup(connection);
            try { connection.closeAndAwait(); } catch (Exception ignored) {}
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
            try { tmux.stopPipePane(sessionName); } catch (Exception ignored) {}
        }
        var fifoPath = fifoPaths.remove(connection.id());
        if (fifoPath != null) {
            try { Files.deleteIfExists(Path.of(fifoPath)); } catch (Exception ignored) {}
        }
    }

    private static int parsePathInt(String value) {
        try { return value != null ? Integer.parseInt(value) : 0; }
        catch (NumberFormatException e) { return 0; }
    }
}
