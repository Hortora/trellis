package io.hortora.trellis.protocol;

import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class GitOps {

    public void commitFiles(Path repoRoot, List<Path> files, String message) throws IOException {
        List<String> addCmd = new ArrayList<>(List.of("git", "add"));
        for (Path f : files) {
            addCmd.add(repoRoot.relativize(f).toString());
        }
        run(repoRoot, addCmd);
        run(repoRoot, List.of("git", "commit", "-m", message));
    }

    private void run(Path workDir, List<String> command) throws IOException {
        try {
            Process p = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new IOException("git command timed out: " + command);
            }
            if (p.exitValue() != 0) {
                String output = new String(p.getInputStream().readAllBytes());
                throw new IOException("git command failed (exit " + p.exitValue() + "): " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git command interrupted", e);
        }
    }
}
