package io.hortora.trellis.repl.input;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionInputStateTest {

    private static final SuggestionSource COMMAND_SOURCE = prefix ->
            List.of("work start", "work status", "work pause", "work resume",
                    "work end", "work next", "git status", "git log", "llm start")
                    .stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();

    @Test
    void filtersOnTextInput() {
        var state = new SuggestionInputState(COMMAND_SOURCE);
        state.insertText("work s");
        assertThat(state.suggestions()).containsExactly("work start", "work status");
        assertThat(state.isDropdownVisible()).isTrue();
    }

    @Test
    void showsAllMatchesForPartialNamespace() {
        var state = new SuggestionInputState(COMMAND_SOURCE);
        state.insertText("work ");
        assertThat(state.suggestions()).hasSize(6);
    }

    @Test
    void hidesDropdownWhenNoMatches() {
        var state = new SuggestionInputState(COMMAND_SOURCE);
        state.insertText("bogus");
        assertThat(state.suggestions()).isEmpty();
        assertThat(state.isDropdownVisible()).isFalse();
    }

    @Test
    void navigatesSuggestions() {
        var state = new SuggestionInputState(COMMAND_SOURCE);
        state.insertText("work s");
        assertThat(state.selectedIndex()).isZero();

        state.selectNext();
        assertThat(state.selectedIndex()).isEqualTo(1);

        state.selectPrevious();
        assertThat(state.selectedIndex()).isZero();
    }

    @Test
    void wrapsAroundAtBounds() {
        var state = new SuggestionInputState(COMMAND_SOURCE);
        state.insertText("work s");
        // 2 suggestions: work start, work status
        state.selectPrevious();
        assertThat(state.selectedIndex()).isEqualTo(1); // wraps to last

        state.selectNext();
        assertThat(state.selectedIndex()).isZero(); // wraps to first
    }

    @Test
    void acceptsSelectedSuggestion() {
        var state = new SuggestionInputState(COMMAND_SOURCE);
        state.insertText("work s");
        state.selectNext(); // select "work status"
        state.acceptSelected();

        assertThat(state.text()).isEqualTo("work status");
        assertThat(state.isDropdownVisible()).isFalse();
    }

    @Test
    void acceptsFirstSuggestionOnTab() {
        var state = new SuggestionInputState(COMMAND_SOURCE);
        state.insertText("work s");
        state.acceptSelected(); // accepts "work start" (index 0)

        assertThat(state.text()).isEqualTo("work start");
    }

    @Test
    void dismissesDropdown() {
        var state = new SuggestionInputState(COMMAND_SOURCE);
        state.insertText("work s");
        assertThat(state.isDropdownVisible()).isTrue();

        state.dismissDropdown();
        assertThat(state.isDropdownVisible()).isFalse();
        assertThat(state.text()).isEqualTo("work s"); // text unchanged
    }

    @Test
    void submitsAndClearsText() {
        var state = new SuggestionInputState(COMMAND_SOURCE);
        state.insertText("work start");
        var submitted = state.submit();

        assertThat(submitted).isEqualTo("work start");
        assertThat(state.text()).isEmpty();
        assertThat(state.isDropdownVisible()).isFalse();
    }

    @Test
    void emptyInputShowsNoSuggestions() {
        var state = new SuggestionInputState(COMMAND_SOURCE);
        assertThat(state.suggestions()).isEmpty();
        assertThat(state.isDropdownVisible()).isFalse();
    }

    @Test
    void backspaceUpdatesFilter() {
        var state = new SuggestionInputState(COMMAND_SOURCE);
        state.insertText("work sta");
        assertThat(state.suggestions()).containsExactly("work start", "work status");

        state.deleteBackward();
        // now "work st" — still 2 matches
        assertThat(state.suggestions()).containsExactly("work start", "work status");
    }

    @Test
    void selectByIndex() {
        var state = new SuggestionInputState(COMMAND_SOURCE);
        state.insertText("work ");
        state.selectIndex(2);
        state.acceptSelected();

        assertThat(state.text()).isEqualTo("work pause");
    }
}
