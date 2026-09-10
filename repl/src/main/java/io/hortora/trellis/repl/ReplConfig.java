package io.hortora.trellis.repl;

public record ReplConfig(
    String repo,
    String slot,
    String issue,
    int sidecarPort,
    String pairedTerminal
) {
    public static ReplConfig fromArgs(String[] args) {
        String repo = "", slot = "", issue = "", paired = "";
        int port = 0;
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--repo" -> repo = args[++i];
                case "--slot" -> slot = args[++i];
                case "--issue" -> issue = args[++i];
                case "--sidecar-port" -> port = Integer.parseInt(args[++i]);
                case "--paired-terminal" -> paired = args[++i];
            }
        }
        return new ReplConfig(repo, slot, issue, port, paired);
    }
}
