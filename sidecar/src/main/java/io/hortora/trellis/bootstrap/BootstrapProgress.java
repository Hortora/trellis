package io.hortora.trellis.bootstrap;

public record BootstrapProgress(
    String projectId,
    String phase,
    String message,
    boolean terminal
) {
    public static BootstrapProgress step(String projectId, String phase, String message) {
        return new BootstrapProgress(projectId, phase, message, false);
    }

    public static BootstrapProgress done(String projectId, String message) {
        return new BootstrapProgress(projectId, "done", message, true);
    }

    public static BootstrapProgress failed(String projectId, String message) {
        return new BootstrapProgress(projectId, "failed", message, true);
    }
}
