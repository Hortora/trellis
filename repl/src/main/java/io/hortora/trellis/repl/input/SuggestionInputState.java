package io.hortora.trellis.repl.input;

import dev.tamboui.widgets.input.TextInputState;

import java.util.List;

public final class SuggestionInputState {

    private final SuggestionSource source;
    private TextInputState textState;
    private List<String> suggestions;
    private int selectedIndex;
    private boolean dropdownVisible;

    public SuggestionInputState(SuggestionSource source) {
        this.source = source;
        this.textState = new TextInputState("");
        this.suggestions = List.of();
        this.selectedIndex = 0;
        this.dropdownVisible = false;
    }

    public String text() {
        return textState.text();
    }

    public TextInputState textState() {
        return textState;
    }

    public List<String> suggestions() {
        return suggestions;
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public boolean isDropdownVisible() {
        return dropdownVisible;
    }

    public void insertText(String text) {
        for (char c : text.toCharArray()) {
            textState.insert(c);
        }
        refreshSuggestions();
    }

    public void insertChar(char c) {
        textState.insert(c);
        refreshSuggestions();
    }

    public void deleteBackward() {
        textState.deleteBackward();
        refreshSuggestions();
    }

    public void deleteForward() {
        textState.deleteForward();
        refreshSuggestions();
    }

    public void moveCursorLeft() {
        textState.moveCursorLeft();
    }

    public void moveCursorRight() {
        textState.moveCursorRight();
    }

    public void moveCursorToStart() {
        textState.moveCursorToStart();
    }

    public void moveCursorToEnd() {
        textState.moveCursorToEnd();
    }

    public void selectNext() {
        if (suggestions.isEmpty()) return;
        selectedIndex = (selectedIndex + 1) % suggestions.size();
    }

    public void selectPrevious() {
        if (suggestions.isEmpty()) return;
        selectedIndex = (selectedIndex - 1 + suggestions.size()) % suggestions.size();
    }

    public void selectIndex(int index) {
        if (index >= 0 && index < suggestions.size()) {
            selectedIndex = index;
        }
    }

    public void acceptSelected() {
        if (suggestions.isEmpty() || selectedIndex >= suggestions.size()) return;
        var selected = suggestions.get(selectedIndex);
        textState = new TextInputState(selected);
        textState.moveCursorToEnd();
        dropdownVisible = false;
        suggestions = List.of();
        selectedIndex = 0;
    }

    public void dismissDropdown() {
        dropdownVisible = false;
    }

    public String submit() {
        var text = textState.text().strip();
        textState = new TextInputState("");
        dropdownVisible = false;
        suggestions = List.of();
        selectedIndex = 0;
        return text;
    }

    private void refreshSuggestions() {
        var prefix = textState.text();
        if (prefix.isEmpty()) {
            suggestions = List.of();
            dropdownVisible = false;
            selectedIndex = 0;
            return;
        }
        suggestions = source.suggest(prefix);
        dropdownVisible = !suggestions.isEmpty();
        selectedIndex = 0;
    }
}
