package io.hortora.trellis.terminal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TmuxManagerTest {

    static final String PREFIX = "trellis-test-";

    TmuxManager manager;
    String sessionName;

    @BeforeAll
    static void checkTmux() throws IOException, InterruptedException {
        var p = new ProcessBuilder("tmux", "-V").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assumeTrue(p.waitFor() == 0, "tmux not available");
    }

    @BeforeEach
    void setUp() {
        manager = new TmuxManager();
        sessionName = PREFIX + UUID.randomUUID().toString().substring(0, 6);
    }

    @AfterEach
    void tearDown() throws IOException, InterruptedException {
        if (manager.hasSession(sessionName)) {
            manager.killSession(sessionName);
        }
    }

    @Test
    void createAndCheckSession() throws IOException, InterruptedException {
        manager.createSession(sessionName, "/tmp");

        assertTrue(manager.hasSession(sessionName));
    }

    @Test
    void killSession() throws IOException, InterruptedException {
        manager.createSession(sessionName, "/tmp");
        manager.killSession(sessionName);

        assertFalse(manager.hasSession(sessionName));
    }

    @Test
    void hasSessionReturnsFalseForNonexistent() throws IOException, InterruptedException {
        assertFalse(manager.hasSession("trellis-nonexistent-session-xyz"));
    }

    @Test
    void listSessionsFiltersByPrefix() throws IOException, InterruptedException {
        manager.createSession(sessionName, "/tmp");

        var sessions = manager.listSessions("trellis-test-");

        assertTrue(sessions.contains(sessionName));
    }

    @Test
    void listSessionsExcludesOtherPrefixes() throws IOException, InterruptedException {
        manager.createSession(sessionName, "/tmp");

        var sessions = manager.listSessions("trellis-prod-");

        assertFalse(sessions.contains(sessionName));
    }

    @Test
    void sendKeysAndCapturePane() throws IOException, InterruptedException {
        manager.createSession(sessionName, "/tmp");
        manager.sendKeys(sessionName, "echo hello-trellis\n");

        Thread.sleep(200);

        String output = manager.capturePane(sessionName, 20);
        assertTrue(output.contains("hello-trellis"), "Expected 'hello-trellis' in: " + output);
    }

    @Test
    void setAndGetOption() throws IOException, InterruptedException {
        manager.createSession(sessionName, "/tmp");

        manager.setOption(sessionName, "@trellis_slot", "5");
        var value = manager.getOption(sessionName, "@trellis_slot");

        assertTrue(value.isPresent());
        assertEquals("5", value.get());
    }

    @Test
    void getOptionReturnsEmptyForUnset() throws IOException, InterruptedException {
        manager.createSession(sessionName, "/tmp");

        var value = manager.getOption(sessionName, "@trellis_nonexistent");

        assertTrue(value.isEmpty());
    }

    @Test
    void resizeWindow() throws IOException, InterruptedException {
        manager.createSession(sessionName, "/tmp");

        assertDoesNotThrow(() -> manager.resizeWindow(sessionName, 120, 40));
    }

    @Test
    void displayMessageReturnsPanePid() throws IOException, InterruptedException {
        manager.createSession(sessionName, "/tmp");
        String panePid = manager.displayMessage(sessionName, "#{pane_pid}");
        assertFalse(panePid.isBlank());
        assertTrue(panePid.matches("\\d+"), "Expected numeric PID, got: " + panePid);
    }

    @Test
    void displayMessageReturnsPaneCurrentCommand() throws IOException, InterruptedException {
        manager.createSession(sessionName, "/tmp");
        String cmd = manager.displayMessage(sessionName, "#{pane_current_command}");
        assertFalse(cmd.isBlank());
        assertTrue(cmd.matches("(bash|zsh|sh|dash|fish)"),
                   "Expected shell command, got: " + cmd);
    }

    @Test
    void forceRedrawProducesOutputThroughPipe() throws Exception {
        manager.createSession(sessionName, "/tmp");
        manager.resizeWindow(sessionName, 80, 24);
        manager.sendKeys(sessionName, "echo MARKER-REDRAW\n");
        Thread.sleep(300);

        // Set up a FIFO and pipe
        String fifoPath = "/tmp/trellis-test-" + sessionName + ".pipe";
        new ProcessBuilder("mkfifo", fifoPath).redirectErrorStream(true).start().waitFor();

        var captured = new StringBuilder();
        var readerThread = Thread.ofVirtual().start(() -> {
            try (var in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                            new java.io.FileInputStream(fifoPath), java.nio.charset.StandardCharsets.UTF_8))) {
                var cbuf = new char[4096];
                int n;
                while ((n = in.read(cbuf)) != -1) {
                    captured.append(cbuf, 0, n);
                }
            } catch (Exception e) {
                // FIFO closed
            }
        });

        // Start pipe-pane, then force redraw — NO capture-pane
        manager.pipePaneToFifo(sessionName, fifoPath);
        manager.forceRedraw(sessionName, 80, 24);
        Thread.sleep(500);

        manager.stopPipePane(sessionName);
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(fifoPath));
        readerThread.join(2000);

        assertTrue(captured.toString().contains("MARKER-REDRAW"),
                   "forceRedraw should cause screen content to flow through pipe-pane, got: "
                   + captured.toString().substring(0, Math.min(200, captured.length())));
    }

}
