package io.hortora.trellis.repl.command;

import java.util.Map;

public record CommandNode(
        String name,
        String description,
        String handler,
        Map<String, CommandNode> children
) {
    public boolean isLeaf() {
        return children == null || children.isEmpty();
    }
}
