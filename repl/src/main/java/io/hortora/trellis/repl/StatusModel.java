package io.hortora.trellis.repl;

import java.util.concurrent.atomic.AtomicReference;

public final class StatusModel {

    private final String repo;
    private final String slot;
    private final String issue;
    private final AtomicReference<String> branch = new AtomicReference<>("main");
    private final AtomicReference<String> workState = new AtomicReference<>("idle");
    private final AtomicReference<String> agentState = new AtomicReference<>("IDLE");
    private final AtomicReference<Long> agentMemory = new AtomicReference<>(0L);
    private final AtomicReference<String> lastResult = new AtomicReference<>("");

    public StatusModel(String repo, String slot, String issue) {
        this.repo = repo;
        this.slot = slot;
        this.issue = issue;
    }

    public void updateBranch(String branch, String state) {
        this.branch.set(branch);
        this.workState.set(state);
    }

    public void updateAgentState(String state, long memoryBytes) {
        this.agentState.set(state);
        this.agentMemory.set(memoryBytes);
    }

    public void setLastResult(String result) {
        this.lastResult.set(result);
    }

    public String render() {
        var mem = agentMemory.get();
        var memStr = mem > 0 ? String.format("%dMB", mem / (1024 * 1024)) : "";
        var agent = agentState.get();
        var agentStr = "IDLE".equals(agent) ? "no agent" : agent + (memStr.isEmpty() ? "" : " " + memStr);

        var slotStr = slot.isEmpty() ? "" : " | " + slot;
        var issueStr = issue.isEmpty() ? "" : " | #" + issue;
        var lastStr = lastResult.get().isEmpty() ? "" : " | " + lastResult.get();

        return String.format(" %s%s%s | %s [%s] %s%s",
                repo, slotStr, issueStr,
                branch.get() + " " + workState.get(),
                agentStr,
                "",
                lastStr);
    }
}
