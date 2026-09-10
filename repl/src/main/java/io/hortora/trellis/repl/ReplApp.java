package io.hortora.trellis.repl;

import dev.tamboui.backend.panama.PanamaBackend;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.AnsiColor;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.input.TextInput;
import dev.tamboui.widgets.input.TextInputState;
import dev.tamboui.widgets.paragraph.Paragraph;

import io.hortora.trellis.repl.command.CommandNode;
import io.hortora.trellis.repl.command.CommandRegistry;
import io.hortora.trellis.repl.command.CommandResult;
import io.hortora.trellis.repl.command.HandlerDispatcher;
import io.hortora.trellis.repl.sidecar.SidecarClient;
import io.hortora.trellis.repl.soredium.SorediumBridge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class ReplApp {

    private final ReplConfig config;
    private final CommandRegistry registry;
    private final HandlerDispatcher dispatcher;
    private final StatusModel statusModel;
    private TextInputState inputState = new TextInputState("");
    private final List<String> outputLines = new ArrayList<>();
    private boolean running = true;

    public ReplApp(ReplConfig config) {
        this.config = config;
        this.registry = CommandRegistry.load(
                ReplApp.class.getResourceAsStream("/commands.yaml"));
        var bridge = new SorediumBridge(
                System.getenv().getOrDefault("SOREDIUM_PATH", System.getProperty("user.home") + "/claude/hortora/soredium"),
                config.repo());
        var sidecar = config.sidecarPort() > 0 ? new SidecarClient(config.sidecarPort()) : null;
        this.dispatcher = new HandlerDispatcher(bridge, sidecar, config);
        this.statusModel = new StatusModel(config.repo(), config.slot(), config.issue());
        if (sidecar != null) {
            sidecar.subscribeSSE("agent:state", data -> {
                // Parse agent state SSE events and update status model
                // Format: {"terminal":"name","state":"RUNNING","memoryBytes":123456}
                try {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var node = mapper.readTree(data);
                    if (node.has("state")) {
                        statusModel.updateAgentState(
                                node.get("state").asText(),
                                node.has("memoryBytes") ? node.get("memoryBytes").asLong() : 0);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    public void run() {
        try (var runner = TuiRunner.create(TuiConfig.builder()
                .backend(new PanamaBackend())
                .tickRate(Duration.ofMillis(100))
                .build())) {
            runner.run(this::handleEvent, this::render);
        } catch (Exception e) {
            System.err.println("REPL error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean handleEvent(Event event, TuiRunner tui) {
        if (!running) {
            tui.quit();
            return false;
        }
        return switch (event) {
            case KeyEvent key -> handleKey(key, tui);
            case TickEvent tick -> false;
            default -> false;
        };
    }

    private boolean handleKey(KeyEvent key, TuiRunner tui) {
        if (key.code() == KeyCode.ENTER) {
            var input = inputState.text().strip();
            if (!input.isEmpty()) {
                processCommand(input);
                inputState = new TextInputState("");
            }
            return true;
        }
        if (key.code() == KeyCode.ESCAPE) {
            running = false;
            return true;
        }
        routeKeyToInput(key);
        return true;
    }

    private void routeKeyToInput(KeyEvent key) {
        switch (key.code()) {
            case KeyCode.BACKSPACE -> inputState.deleteBackward();
            case KeyCode.DELETE -> inputState.deleteForward();
            case KeyCode.LEFT -> inputState.moveCursorLeft();
            case KeyCode.RIGHT -> inputState.moveCursorRight();
            case KeyCode.HOME -> inputState.moveCursorToStart();
            case KeyCode.END -> inputState.moveCursorToEnd();
            default -> {
                if (key.code() == KeyCode.CHAR) {
                    inputState.insert(key.character());
                }
            }
        }
    }

    private void processCommand(String input) {
        if ("quit".equals(input) || "exit".equals(input)) {
            running = false;
            return;
        }
        outputLines.add("> " + input);

        var node = registry.resolve(input);
        if (node == null) {
            outputLines.add("unknown command: " + input);
            return;
        }
        if (node.handler() == null) {
            var completions = registry.complete(input + " ");
            outputLines.add("subcommands: " + String.join(", ", completions));
            return;
        }
        var extraArgs = input.substring(input.lastIndexOf(node.name()) + node.name().length()).strip();
        var args = extraArgs.isEmpty() ? new String[0] : extraArgs.split("\\s+");
        var result = dispatcher.dispatch(node.handler(), args);
        switch (result) {
            case CommandResult.Output o -> {
                for (var line : o.text().split("\n")) {
                    outputLines.add(line);
                }
            }
            case CommandResult.Error e -> outputLines.add("ERROR: " + e.message());
        }
    }

    private void render(Frame frame) {
        var areas = Layout.vertical()
                .constraints(
                        Constraint.length(1),
                        Constraint.fill(),
                        Constraint.length(3)
                )
                .split(frame.area());

        renderStatusBar(frame, areas.get(0));
        renderOutput(frame, areas.get(1));
        renderInput(frame, areas.get(2));
    }

    private void renderStatusBar(Frame frame, Rect area) {
        frame.renderWidget(
                Paragraph.from(Line.styled(statusModel.render(), Style.EMPTY.fg(new Color.Ansi(AnsiColor.CYAN)))),
                area);
    }

    private void renderOutput(Frame frame, Rect area) {
        var block = Block.builder()
                .borders(Borders.ALL)
                .title(" trellis repl ")
                .build();
        var inner = block.inner(area);
        frame.renderWidget(block, area);

        int visibleLines = inner.height();
        int start = Math.max(0, outputLines.size() - visibleLines);
        var lines = new ArrayList<Line>();
        for (int i = start; i < outputLines.size(); i++) {
            lines.add(Line.styled(outputLines.get(i), Style.EMPTY));
        }
        if (!lines.isEmpty()) {
            frame.renderWidget(Paragraph.from(dev.tamboui.text.Text.from(lines)), inner);
        }
    }

    private void renderInput(Frame frame, Rect area) {
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
                .renderWithCursor(inner, frame.buffer(), inputState, frame);
    }
}
