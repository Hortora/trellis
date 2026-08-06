package io.hortora.trellis.mcp;

public record ActionDescriptor(
        String name,
        String description,
        String source,
        String tool,
        String operation
) {
    public static ActionDescriptor backend(String name, String description, String tool, String operation) {
        return new ActionDescriptor(name, description, "backend", tool, operation);
    }
}
