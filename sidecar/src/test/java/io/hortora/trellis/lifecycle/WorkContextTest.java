package io.hortora.trellis.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkContextTest {

    @Test
    void slotContextKeyFormat() {
        var ctx = new WorkContext.SlotContext("3");
        assertEquals("slot-3", ctx.key());
    }

    @Test
    void repoContextKeyFormat() {
        var ctx = new WorkContext.RepoContext("engine");
        assertEquals("repo-engine", ctx.key());
    }

    @Test
    void parseSlotContext() {
        var ctx = WorkContext.parse("slot-3");
        assertInstanceOf(WorkContext.SlotContext.class, ctx);
        assertEquals("3", ((WorkContext.SlotContext) ctx).slotId());
    }

    @Test
    void parseRepoContext() {
        var ctx = WorkContext.parse("repo-engine");
        assertInstanceOf(WorkContext.RepoContext.class, ctx);
        assertEquals("engine", ((WorkContext.RepoContext) ctx).repoName());
    }

    @Test
    void parseInvalidThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkContext.parse("unknown-ctx"));
    }
}
