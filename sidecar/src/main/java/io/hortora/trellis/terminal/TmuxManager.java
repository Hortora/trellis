package io.hortora.trellis.terminal;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@ApplicationScoped
public class TmuxManager {

    public void createSession(String name, String workingDir) throws IOException, InterruptedException {
        run("tmux", "new-session", "-d", "-s", name, "-c", workingDir);
        sendKeys(name, "cd " + workingDir + " && clear\n");
    }

    public void killSession(String name) throws IOException, InterruptedException {
        run("tmux", "kill-session", "-t", name);
    }

    public boolean hasSession(String name) throws IOException, InterruptedException {
        var p = new ProcessBuilder("tmux", "has-session", "-t", name)
                .redirectErrorStream(true).start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        return p.waitFor() == 0;
    }

    public List<String> listSessions(String prefix) throws IOException, InterruptedException {
        var pb = new ProcessBuilder("tmux", "list-sessions", "-F", "#{session_name}");
        pb.redirectErrorStream(true);
        var process = pb.start();
        List<String> sessions;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            sessions = reader.lines()
                    .filter(l -> !l.isBlank() && l.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        int exit = process.waitFor();
        return exit == 0 ? sessions : List.of();
    }

    public void sendKeys(String name, String text) throws IOException, InterruptedException {
        run("tmux", "send-keys", "-t", name, "-l", text);
    }

    public String capturePane(String name, int lines) throws IOException, InterruptedException {
        var p = new ProcessBuilder("tmux", "capture-pane", "-t", name, "-e", "-p", "-S", String.valueOf(-lines))
                .redirectErrorStream(true).start();
        try (var in = p.getInputStream()) {
            var output = new String(in.readAllBytes());
            p.waitFor();
            return output;
        }
    }

    public String displayMessage(String sessionName, String format)
            throws IOException, InterruptedException {
        var p = new ProcessBuilder("tmux", "display-message", "-t", sessionName, "-p", format)
                        .redirectErrorStream(false).start();
        var output = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return output;
    }


    public void setOption(String name, String key, String value) throws IOException, InterruptedException {
        run("tmux", "set-option", "-t", name, key, value);
    }

    public Optional<String> getOption(String name, String key) throws IOException, InterruptedException {
        var p = new ProcessBuilder("tmux", "show-options", "-t", name, "-v", key)
                .redirectErrorStream(false).start();
        var value = new String(p.getInputStream().readAllBytes()).trim();
        int exit = p.waitFor();
        if (exit != 0 || value.isBlank()) return Optional.empty();
        return Optional.of(value);
    }

    public void resizeWindow(String name, int cols, int rows) throws IOException, InterruptedException {
        run("tmux", "resize-window", "-t", name, "-x", String.valueOf(cols), "-y", String.valueOf(rows));
    }

    public void forceRedraw(String name, int cols, int rows) throws IOException, InterruptedException {
        resizeWindow(name, cols - 1, rows);
        Thread.sleep(50);
        resizeWindow(name, cols, rows);
    }


    public void pipePaneToFifo(String name, String fifoPath) throws IOException, InterruptedException {
        run("tmux", "pipe-pane", "-t", name, "cat > " + fifoPath);
    }

    public void stopPipePane(String name) throws IOException, InterruptedException {
        run("tmux", "pipe-pane", "-t", name);
    }

    private void run(String... command) throws IOException, InterruptedException {
        var p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().transferTo(OutputStream.nullOutputStream());
        p.waitFor();
    }
}
