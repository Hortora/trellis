package io.hortora.trellis.repl.command;

import java.util.List;

public record GoalNode(
        String name,
        String description,
        List<GoalStep> steps,
        List<String> execute
) {
    public record GoalStep(String prompt, String source, String type, Object defaultValue) {}
}
