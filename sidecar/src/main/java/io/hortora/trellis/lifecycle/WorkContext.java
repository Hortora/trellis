package io.hortora.trellis.lifecycle;

public sealed interface WorkContext {
    record SlotContext(String slotId) implements WorkContext {
        @Override public String key() { return "slot-" + slotId; }
    }
    record RepoContext(String repoName) implements WorkContext {
        @Override public String key() { return "repo-" + repoName; }
    }

    String key();

    static WorkContext parse(String contextId) {
        if (contextId.startsWith("slot-")) return new SlotContext(contextId.substring(5));
        if (contextId.startsWith("repo-")) return new RepoContext(contextId.substring(5));
        throw new IllegalArgumentException("Unknown context type: " + contextId);
    }
}
