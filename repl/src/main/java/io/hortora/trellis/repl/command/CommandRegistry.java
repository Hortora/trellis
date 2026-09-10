package io.hortora.trellis.repl.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CommandRegistry {

    private final Map<String, CommandNode> roots;
    private final Map<String, GoalNode> goals;

    private CommandRegistry(Map<String, CommandNode> roots, Map<String, GoalNode> goals) {
        this.roots = roots;
        this.goals = goals;
    }

    @SuppressWarnings("unchecked")
    public static CommandRegistry load(InputStream yaml) {
        var mapper = new ObjectMapper(new YAMLFactory());
        try {
            var raw = mapper.readValue(yaml, Map.class);
            var commandsRaw = (Map<String, Object>) raw.getOrDefault("commands", Map.of());
            var goalsRaw = (Map<String, Object>) raw.getOrDefault("goals", Map.of());
            return new CommandRegistry(parseNodes(commandsRaw), parseGoals(goalsRaw));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load commands.yaml", e);
        }
    }

    public CommandNode resolve(String input) {
        var parts = input.trim().split("\\s+");
        var current = roots;
        CommandNode last = null;
        for (var part : parts) {
            if (current == null) return null;
            var node = current.get(part);
            if (node == null) return null;
            last = node;
            current = node.children();
        }
        return last;
    }

    public List<String> complete(String prefix) {
        var trimmed = prefix.trim();
        if (trimmed.isEmpty()) {
            return roots.keySet().stream().sorted().toList();
        }

        var parts = trimmed.split("\\s+", -1);
        var current = roots;
        for (int i = 0; i < parts.length - 1; i++) {
            if (current == null) return List.of();
            var node = current.get(parts[i]);
            if (node == null || node.children() == null) return List.of();
            current = node.children();
        }
        if (current == null) return List.of();

        var partial = parts[parts.length - 1];
        return current.keySet().stream()
                .filter(k -> k.startsWith(partial))
                .sorted()
                .toList();
    }

    public Map<String, GoalNode> goals() {
        return goals;
    }

    public Map<String, CommandNode> roots() {
        return roots;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CommandNode> parseNodes(Map<String, Object> raw) {
        var result = new LinkedHashMap<String, CommandNode>();
        for (var entry : raw.entrySet()) {
            var val = (Map<String, Object>) entry.getValue();
            var desc = (String) val.getOrDefault("description", "");
            var handler = (String) val.get("handler");
            var childrenRaw = (Map<String, Object>) val.get("children");
            var children = childrenRaw != null ? parseNodes(childrenRaw) : Map.<String, CommandNode>of();
            result.put(entry.getKey(), new CommandNode(entry.getKey(), desc, handler, children));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, GoalNode> parseGoals(Map<String, Object> raw) {
        var result = new LinkedHashMap<String, GoalNode>();
        for (var entry : raw.entrySet()) {
            var val = (Map<String, Object>) entry.getValue();
            var desc = (String) val.getOrDefault("description", "");
            var stepsRaw = (List<Map<String, Object>>) val.getOrDefault("steps", List.of());
            var steps = stepsRaw.stream().map(s -> new GoalNode.GoalStep(
                    (String) s.get("prompt"),
                    (String) s.get("source"),
                    (String) s.get("type"),
                    s.get("default")
            )).toList();
            var execute = (List<String>) val.getOrDefault("execute", List.of());
            result.put(entry.getKey(), new GoalNode(entry.getKey(), desc, steps, execute));
        }
        return result;
    }
}
