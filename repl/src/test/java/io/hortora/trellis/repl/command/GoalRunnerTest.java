package io.hortora.trellis.repl.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoalRunnerTest {

    @Test
    void collectsAnswersAndProducesSummary() {
        var goal = new GoalNode("test-goal", "Test", List.of(
                new GoalNode.GoalStep("Pick a repo", null, "text", null),
                new GoalNode.GoalStep("Pick an issue", null, "text", null),
                new GoalNode.GoalStep(null, null, "summary", null),
                new GoalNode.GoalStep("Proceed?", null, "confirm", null)
        ), List.of());

        var runner = new GoalRunner(null);
        runner.start(goal);

        assertThat(runner.isActive()).isTrue();
        assertThat(runner.currentPrompt()).isEqualTo("Pick a repo");
        runner.handleInput("my-repo");
        assertThat(runner.currentPrompt()).isEqualTo("Pick an issue");
        runner.handleInput("42");
        // summary step — prompt returns the summary text
        assertThat(runner.currentPrompt()).contains("my-repo").contains("42");
        assertThat(runner.summary()).contains("my-repo").contains("42");
        runner.handleInput("");  // acknowledge summary
        assertThat(runner.currentPrompt()).isEqualTo("Proceed?");
    }

    @Test
    void cancelOnNo() {
        var goal = new GoalNode("test", "Test", List.of(
                new GoalNode.GoalStep("Continue?", null, "confirm", null)
        ), List.of());

        var runner = new GoalRunner(null);
        runner.start(goal);
        var result = runner.handleInput("n");

        assertThat(runner.isActive()).isFalse();
        assertThat(result).isInstanceOf(CommandResult.Output.class);
        assertThat(((CommandResult.Output) result).text()).contains("cancelled");
    }

    @Test
    void executesOnCompletion() {
        var goal = new GoalNode("test", "Test", List.of(
                new GoalNode.GoalStep("Name?", null, "text", null),
                new GoalNode.GoalStep("Go?", null, "confirm", null)
        ), List.of("shell:echo done"));

        var dispatcher = new HandlerDispatcher(null, null,
                new io.hortora.trellis.repl.ReplConfig(
                        System.getProperty("java.io.tmpdir"), "", "", 0, ""));
        var runner = new GoalRunner(dispatcher);
        runner.start(goal);

        runner.handleInput("test-name");
        var result = runner.handleInput("y");

        assertThat(runner.isActive()).isFalse();
        assertThat(result).isInstanceOf(CommandResult.Output.class);
        assertThat(((CommandResult.Output) result).text()).contains("done");
    }

    @Test
    void notActiveBeforeStart() {
        var runner = new GoalRunner(null);
        assertThat(runner.isActive()).isFalse();
        assertThat(runner.currentPrompt()).isNull();
    }
}
