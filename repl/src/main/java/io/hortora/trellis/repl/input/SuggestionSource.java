package io.hortora.trellis.repl.input;

import java.util.List;

@FunctionalInterface
public interface SuggestionSource {
    List<String> suggest(String prefix);
}
