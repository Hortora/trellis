package io.hortora.trellis.repl.command;

public sealed interface CommandResult {
    record Output(String text) implements CommandResult {}
    record Error(String message) implements CommandResult {}
}
