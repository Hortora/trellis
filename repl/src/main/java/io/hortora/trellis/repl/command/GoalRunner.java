package io.hortora.trellis.repl.command;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GoalRunner {

    private final HandlerDispatcher dispatcher;
    private GoalNode activeGoal;
    private int stepIndex;
    private final Map<String, String> answers = new LinkedHashMap<>();

    public GoalRunner(HandlerDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void start(GoalNode goal) {
        this.activeGoal = goal;
        this.stepIndex = 0;
        this.answers.clear();
        skipNonInteractiveSteps();
    }

    public boolean isActive() {
        return activeGoal != null;
    }

    public String currentPrompt() {
        if (!isActive() || stepIndex >= activeGoal.steps().size()) return null;
        var step = activeGoal.steps().get(stepIndex);
        if ("summary".equals(step.type())) return summary();
        return step.prompt();
    }

    public GoalNode.GoalStep currentStep() {
        if (!isActive() || stepIndex >= activeGoal.steps().size()) return null;
        return activeGoal.steps().get(stepIndex);
    }

    public CommandResult handleInput(String input) {
        if (!isActive()) return null;
        var step = activeGoal.steps().get(stepIndex);

        switch (step.type()) {
            case "text", "select" -> {
                var key = deriveKey(step.prompt());
                answers.put(key, input);
                stepIndex++;
            }
            case "confirm" -> {
                if ("n".equalsIgnoreCase(input) || "no".equalsIgnoreCase(input)) {
                    activeGoal = null;
                    return new CommandResult.Output("Goal cancelled.");
                }
                stepIndex++;
            }
            case "summary" -> stepIndex++;
        }

        skipNonInteractiveSteps();

        if (isActive() && stepIndex >= activeGoal.steps().size()) {
            return executeGoal();
        }
        return null;
    }

    public String summary() {
        var sb = new StringBuilder("Summary:\n");
        answers.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
        return sb.toString();
    }

    private void skipNonInteractiveSteps() {
        while (isActive() && stepIndex < activeGoal.steps().size()
                && "summary".equals(activeGoal.steps().get(stepIndex).type())
                && stepIndex + 1 < activeGoal.steps().size()) {
            break;
        }
    }

    private String deriveKey(String prompt) {
        if (prompt == null) return "value_" + stepIndex;
        return prompt.toLowerCase().replaceAll("[^a-z0-9]", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    private CommandResult executeGoal() {
        var results = new StringBuilder();
        for (var cmd : activeGoal.execute()) {
            var expanded = cmd;
            for (var entry : answers.entrySet()) {
                expanded = expanded.replace("{" + entry.getKey() + "}", entry.getValue());
            }
            var colonIdx = expanded.indexOf(':');
            if (colonIdx < 0) continue;
            var handler = expanded;
            var args = new String[0];
            var spaceIdx = expanded.indexOf(' ', colonIdx);
            if (spaceIdx > 0) {
                handler = expanded.substring(0, spaceIdx);
                args = expanded.substring(spaceIdx + 1).strip().split("\\s+");
            }
            if (dispatcher != null) {
                var result = dispatcher.dispatch(handler, args);
                switch (result) {
                    case CommandResult.Output o -> results.append(o.text()).append("\n");
                    case CommandResult.Error e -> results.append("ERROR: ").append(e.message()).append("\n");
                }
            }
        }
        activeGoal = null;
        return new CommandResult.Output(results.toString());
    }
}
