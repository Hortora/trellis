package io.hortora.trellis.repl.input;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandHistoryTest {

    @Test
    void previousReturnsEmptyWhenNoHistory() {
        var history = new CommandHistory();
        assertThat(history.previous()).isEmpty();
    }

    @Test
    void previousRecallsLastCommand() {
        var history = new CommandHistory();
        history.add("work status");
        assertThat(history.previous()).hasValue("work status");
    }

    @Test
    void previousNavigatesBackwardThroughHistory() {
        var history = new CommandHistory();
        history.add("git status");
        history.add("work next");
        history.add("project build");
        assertThat(history.previous()).hasValue("project build");
        assertThat(history.previous()).hasValue("work next");
        assertThat(history.previous()).hasValue("git status");
    }

    @Test
    void previousStopsAtOldestCommand() {
        var history = new CommandHistory();
        history.add("first");
        assertThat(history.previous()).hasValue("first");
        assertThat(history.previous()).hasValue("first");
    }

    @Test
    void nextReturnsEmptyWhenAtEnd() {
        var history = new CommandHistory();
        history.add("work status");
        assertThat(history.next()).isEmpty();
    }

    @Test
    void nextNavigatesForwardAfterPrevious() {
        var history = new CommandHistory();
        history.add("git status");
        history.add("work next");
        history.previous();
        history.previous();
        assertThat(history.next()).hasValue("work next");
    }

    @Test
    void nextPastEndReturnsEmpty() {
        var history = new CommandHistory();
        history.add("git status");
        history.previous();
        assertThat(history.next()).isEmpty();
        assertThat(history.next()).isEmpty();
    }

    @Test
    void addResetsPositionToEnd() {
        var history = new CommandHistory();
        history.add("first");
        history.add("second");
        history.previous();
        history.add("third");
        assertThat(history.previous()).hasValue("third");
    }

    @Test
    void addIgnoresEmptyAndBlankStrings() {
        var history = new CommandHistory();
        history.add("");
        history.add("   ");
        assertThat(history.previous()).isEmpty();
    }

    @Test
    void addSuppressesDuplicateConsecutiveCommands() {
        var history = new CommandHistory();
        history.add("git status");
        history.add("git status");
        history.add("git status");
        assertThat(history.previous()).hasValue("git status");
        assertThat(history.previous()).hasValue("git status");
    }

    @Test
    void addAllowsNonConsecutiveDuplicates() {
        var history = new CommandHistory();
        history.add("git status");
        history.add("work next");
        history.add("git status");
        assertThat(history.previous()).hasValue("git status");
        assertThat(history.previous()).hasValue("work next");
        assertThat(history.previous()).hasValue("git status");
    }
}
