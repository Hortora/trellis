package io.hortora.trellis.repl.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CommandHistory {

    private final List<String> entries = new ArrayList<>();
    private int position = 0;

    public void add(final String command) {
        if (command == null || command.isBlank()) return;
        if (!entries.isEmpty() && entries.getLast().equals(command)) return;
        entries.add(command);
        position = entries.size();
    }

    public Optional<String> previous() {
        if (entries.isEmpty()) return Optional.empty();
        if (position > 0) position--;
        return Optional.of(entries.get(position));
    }

    public Optional<String> next() {
        if (position >= entries.size() - 1) {
            position = entries.size();
            return Optional.empty();
        }
        position++;
        return Optional.of(entries.get(position));
    }
}
