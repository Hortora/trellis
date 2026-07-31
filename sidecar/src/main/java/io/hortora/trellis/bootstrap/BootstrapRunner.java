package io.hortora.trellis.bootstrap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class BootstrapRunner {

    private static final Logger LOG = Logger.getLogger(BootstrapRunner.class);

    @Inject
    Event<BootstrapProgress> progressEvent;

    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    public void bootstrap(ProjectEntry project, Path targetDir) {
        if (running.putIfAbsent(project.id(), true) != null) {
            progressEvent.fire(BootstrapProgress.failed(project.id(), "Bootstrap already in progress"));
            return;
        }

        try {
            doBootstrap(project, targetDir);
        } finally {
            running.remove(project.id());
        }
    }

    public boolean isRunning(String projectId) {
        return running.containsKey(projectId);
    }

    private void doBootstrap(ProjectEntry project, Path targetDir) {
        try {
            Files.createDirectories(targetDir);

            progressEvent.fire(BootstrapProgress.step(project.id(), "clone",
                "Cloning " + project.parentRepoUrl()));
            int cloneExit = runProcess(targetDir.getParent(),
                "git", "clone", project.parentRepoUrl(), targetDir.getFileName().toString());
            if (cloneExit != 0) {
                progressEvent.fire(BootstrapProgress.failed(project.id(), "git clone failed (exit " + cloneExit + ")"));
                return;
            }

            if (project.setupCommand() != null && !project.setupCommand().isBlank()) {
                progressEvent.fire(BootstrapProgress.step(project.id(), "setup",
                    "Running " + project.setupCommand()));
                int setupExit = runProcess(targetDir, "bash", "-c", project.setupCommand());
                if (setupExit != 0) {
                    progressEvent.fire(BootstrapProgress.failed(project.id(), "Setup command failed (exit " + setupExit + ")"));
                    return;
                }
            }

            progressEvent.fire(BootstrapProgress.done(project.id(),
                "Project " + project.name() + " bootstrapped at " + targetDir));

        } catch (Exception e) {
            LOG.errorf(e, "Bootstrap failed for %s", project.id());
            progressEvent.fire(BootstrapProgress.failed(project.id(), e.getMessage()));
        }
    }

    private int runProcess(Path workDir, String... command) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        var process = pb.start();
        process.getInputStream().transferTo(System.out);
        return process.waitFor();
    }
}
