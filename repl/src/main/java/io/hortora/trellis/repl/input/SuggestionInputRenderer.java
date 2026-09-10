package io.hortora.trellis.repl.input;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.AnsiColor;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.paragraph.Paragraph;

import java.util.ArrayList;
import java.util.List;

public final class SuggestionInputRenderer {

    private static final Style NORMAL = Style.EMPTY;
    private static final Style SELECTED = Style.EMPTY
            .fg(new Color.Ansi(AnsiColor.BLACK))
            .bg(new Color.Ansi(AnsiColor.CYAN));
    private static final Style DIM = Style.EMPTY.fg(new Color.Ansi(AnsiColor.BRIGHT_BLACK));

    private SuggestionInputRenderer() {}

    public static void renderInput(Frame frame, Rect area, SuggestionInputState state) {
        var block = Block.builder()
                .borders(Borders.ALL)
                .title(" > ")
                .build();
        var inner = block.inner(area);
        frame.renderWidget(block, area);

        TextInput.builder()
                .placeholder("type a command...")
                .style(Style.EMPTY)
                .build()
                .renderWithCursor(inner, frame.buffer(), state.textState(), frame);
    }

    public static void renderDropdown(Frame frame, Rect overlayArea, SuggestionInputState state) {
        if (!state.isDropdownVisible() || state.suggestions().isEmpty()) return;

        var suggestions = state.suggestions();
        int maxVisible = Math.min(suggestions.size(), overlayArea.height());
        if (maxVisible <= 0) return;

        int dropdownHeight = maxVisible;
        int y = overlayArea.y() + overlayArea.height() - dropdownHeight;
        var dropdownRect = new Rect(overlayArea.x(), y, overlayArea.width(), dropdownHeight);

        var buf = frame.buffer();
        for (int row = 0; row < dropdownHeight; row++) {
            for (int col = 0; col < dropdownRect.width(); col++) {
                buf.set(dropdownRect.x() + col, dropdownRect.y() + row,
                        new dev.tamboui.buffer.Cell(" ", Style.EMPTY.bg(new Color.Ansi(AnsiColor.BLACK))));
            }
        }

        int offset = Math.max(0, state.selectedIndex() - maxVisible + 1);
        var lines = new ArrayList<Line>();
        for (int i = offset; i < Math.min(offset + maxVisible, suggestions.size()); i++) {
            var text = suggestions.get(i);
            var prefix = (i == state.selectedIndex()) ? " > " : "   ";
            var style = (i == state.selectedIndex()) ? SELECTED : NORMAL;
            lines.add(Line.styled(prefix + text, style));
        }

        frame.renderWidget(Paragraph.from(Text.from(lines)), dropdownRect);
    }
}
