package io.hortora.trellis.repl.command;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CommandRegistryTest {

    @Test
    void loadsCommandTreeFromYaml() {
        var yaml = """
                commands:
                  work:
                    description: "Work lifecycle"
                    children:
                      status:
                        description: "Show status"
                        handler: "soredium:status"
                      pause:
                        description: "Pause work"
                        handler: "soredium:pause"
                  git:
                    description: "Git operations"
                    children:
                      status:
                        description: "Git status"
                        handler: "shell:git status"
                """;
        var registry = load(yaml);
        assertThat(registry.resolve("work status")).isNotNull();
        assertThat(registry.resolve("work status").handler()).isEqualTo("soredium:status");
        assertThat(registry.resolve("git status")).isNotNull();
        assertThat(registry.resolve("git status").handler()).isEqualTo("shell:git status");
    }

    @Test
    void resolvesNamespaceRoot() {
        var yaml = """
                commands:
                  work:
                    description: "Work lifecycle"
                    children:
                      start:
                        description: "Start work"
                        handler: "soredium:start"
                """;
        var registry = load(yaml);
        var workNode = registry.resolve("work");
        assertThat(workNode).isNotNull();
        assertThat(workNode.name()).isEqualTo("work");
        assertThat(workNode.isLeaf()).isFalse();
    }

    @Test
    void returnsNullForUnknownCommand() {
        var yaml = """
                commands:
                  work:
                    description: "Work lifecycle"
                    children:
                      status:
                        description: "Show status"
                        handler: "soredium:status"
                """;
        var registry = load(yaml);
        assertThat(registry.resolve("bogus")).isNull();
        assertThat(registry.resolve("work bogus")).isNull();
    }

    @Test
    void completesPartialInput() {
        var yaml = """
                commands:
                  work:
                    description: "Work lifecycle"
                    children:
                      start:
                        description: "Start work"
                        handler: "soredium:start"
                      status:
                        description: "Show status"
                        handler: "soredium:status"
                      stop:
                        description: "Stop work"
                        handler: "soredium:stop"
                """;
        var registry = load(yaml);
        assertThat(registry.complete("work st")).containsExactly("start", "status", "stop");
        assertThat(registry.complete("work sta")).containsExactly("start", "status");
    }

    @Test
    void completesTopLevel() {
        var yaml = """
                commands:
                  git:
                    description: "Git"
                    children:
                      status:
                        handler: "shell:git status"
                  work:
                    description: "Work"
                    children:
                      start:
                        handler: "soredium:start"
                """;
        var registry = load(yaml);
        assertThat(registry.complete("")).containsExactlyInAnyOrder("git", "work");
        assertThat(registry.complete("g")).containsExactly("git");
    }

    @Test
    void loadsGoals() {
        var yaml = """
                goals:
                  start-work:
                    description: "Start working on an issue"
                    steps:
                      - prompt: "Which issue?"
                        source: "github:open-issues"
                        type: select
                      - type: summary
                      - type: confirm
                        prompt: "Proceed?"
                    execute:
                      - "soredium:start --issue {issue}"
                """;
        var registry = load(yaml);
        assertThat(registry.goals()).containsKey("start-work");
        var goal = registry.goals().get("start-work");
        assertThat(goal.steps()).hasSize(3);
        assertThat(goal.steps().get(0).type()).isEqualTo("select");
        assertThat(goal.execute()).containsExactly("soredium:start --issue {issue}");
    }

    private static CommandRegistry load(String yaml) {
        return CommandRegistry.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
