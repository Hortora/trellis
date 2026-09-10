package io.hortora.trellis.repl.command;

import io.hortora.trellis.repl.ReplConfig;
import io.hortora.trellis.repl.sidecar.SidecarClient;
import io.hortora.trellis.repl.soredium.SorediumBridge;
import io.hortora.trellis.repl.soredium.SorediumEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class HandlerDispatcher {

    private final SorediumBridge bridge;
    private final SidecarClient sidecar;
    private final ReplConfig config;

    public HandlerDispatcher(SorediumBridge bridge, SidecarClient sidecar, ReplConfig config) {
        this.bridge = bridge;
        this.sidecar = sidecar;
        this.config = config;
    }

    public CommandResult dispatch(String handler, String[] args) {
        var colonIdx = handler.indexOf(':');
        if (colonIdx < 0) return new CommandResult.Error("Invalid handler: " + handler);

        var prefix = handler.substring(0, colonIdx);
        var value = handler.substring(colonIdx + 1);

        return switch (prefix) {
            case "shell" -> executeShell(value, args);
            case "soredium" -> executeSoredium(value, args);
            case "llm" -> executeLlm(value, args);
            default -> new CommandResult.Error("Unknown handler prefix: " + prefix);
        };
    }

    private CommandResult executeShell(String command, String[] args) {
        try {
            var fullCmd = command;
            if (args.length > 0) {
                fullCmd += " " + String.join(" ", args);
            }
            var pb = new ProcessBuilder("sh", "-c", fullCmd)
                    .directory(new File(config.repo().isEmpty() ? "." : config.repo()))
                    .redirectErrorStream(true);
            var process = pb.start();
            var output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            return new CommandResult.Output(output);
        } catch (Exception e) {
            return new CommandResult.Error(e.getMessage());
        }
    }

    private CommandResult executeSoredium(String command, String[] args) {
        if (bridge == null) return new CommandResult.Error("Soredium bridge not configured");
        try {
            var kwargs = new HashMap<String, String>();
            for (int i = 0; i < args.length - 1; i += 2) {
                kwargs.put(args[i].replaceFirst("^--", ""), args[i + 1]);
            }
            var events = bridge.execute(command, kwargs);
            var sb = new StringBuilder();
            for (var event : events) {
                sb.append(formatEvent(event)).append("\n");
            }
            return new CommandResult.Output(sb.toString());
        } catch (Exception e) {
            return new CommandResult.Error(e.getMessage());
        }
    }

    private CommandResult executeLlm(String operation, String[] args) {
        if (sidecar == null) return new CommandResult.Error("Sidecar not configured");
        var terminal = config.pairedTerminal();
        try {
            return switch (operation) {
                case "start" -> {
                    sidecar.startAgent(terminal);
                    yield new CommandResult.Output("Agent started");
                }
                case "stop" -> {
                    sidecar.stopAgent(terminal);
                    yield new CommandResult.Output("Agent stopped");
                }
                case "sweep" -> {
                    sidecar.sendInput(terminal, "/forage SWEEP");
                    yield new CommandResult.Output("Sweep dispatched");
                }
                case "squash" -> {
                    sidecar.sendInput(terminal, "/git-squash");
                    yield new CommandResult.Output("Squash dispatched");
                }
                case "review" -> {
                    sidecar.sendInput(terminal, "/code-review");
                    yield new CommandResult.Output("Review dispatched");
                }
                case "blog" -> {
                    sidecar.sendInput(terminal, "/write-content diary");
                    yield new CommandResult.Output("Blog dispatched");
                }
                case "end" -> {
                    sidecar.sendInput(terminal, "work end");
                    yield new CommandResult.Output("LLM work-end dispatched");
                }
                default -> {
                    var text = operation + (args.length > 0 ? " " + String.join(" ", args) : "");
                    sidecar.sendInput(terminal, text);
                    yield new CommandResult.Output("Sent to LLM");
                }
            };
        } catch (Exception e) {
            return new CommandResult.Error("LLM dispatch failed: " + e.getMessage());
        }
    }

    private String formatEvent(SorediumEvent event) {
        return switch (event.type()) {
            case "StepProgress" -> "→ " + event.data().getOrDefault("step", "") +
                    ": " + event.data().getOrDefault("detail", "");
            case "StatusReady" -> String.format("Branch: %s | State: %s | Main: %s",
                    event.data().get("branch"), event.data().get("state"), event.data().get("on_main"));
            case "CommandFailed" -> "ERROR: " + event.data().get("error") +
                    " — " + event.data().get("detail");
            case "BranchCreated" -> "Branch created: " + event.data().get("branch");
            case "WorkEnded" -> "Work ended: " + event.data().get("branch");
            case "Paused" -> "Paused: " + event.data().get("branch");
            case "Resumed" -> "Resumed: " + event.data().get("branch");
            case "PlanAdvanced" -> "Advanced to: #" + event.data().get("next_issue");
            default -> event.type() + ": " + event.data();
        };
    }
}
