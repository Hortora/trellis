package io.hortora.trellis.repl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatusModelTest {

    @Test
    void rendersBasicStatus() {
        var model = new StatusModel("my-repo", "slot-1", "75");
        var rendered = model.render();
        assertThat(rendered).contains("my-repo").contains("slot-1").contains("#75");
    }

    @Test
    void rendersBranchAndState() {
        var model = new StatusModel("my-repo", "", "75");
        model.updateBranch("issue-75-repl", "active");
        var rendered = model.render();
        assertThat(rendered).contains("issue-75-repl").contains("active");
    }

    @Test
    void rendersAgentState() {
        var model = new StatusModel("my-repo", "", "75");
        model.updateAgentState("RUNNING", 256_000_000);
        var rendered = model.render();
        assertThat(rendered).contains("RUNNING").contains("244MB");
    }

    @Test
    void rendersIdleAgentCompactly() {
        var model = new StatusModel("my-repo", "", "75");
        model.updateAgentState("IDLE", 0);
        var rendered = model.render();
        assertThat(rendered).contains("no agent");
        assertThat(rendered).doesNotContain("IDLE");
    }

    @Test
    void rendersLastResult() {
        var model = new StatusModel("repo", "", "1");
        model.setLastResult("OK");
        assertThat(model.render()).contains("OK");
    }

    @Test
    void defaultsToMainIdle() {
        var model = new StatusModel("repo", "", "");
        var rendered = model.render();
        assertThat(rendered).contains("main").contains("idle");
    }
}
