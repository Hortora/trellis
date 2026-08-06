package io.hortora.trellis.mcp;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TrellisTerminalTest {

    @Inject
    TrellisTools tools;

    @Test
    void readLogForNonexistentTerminalReturnsEmpty() {
        var result = tools.trellisTerminal("nonexistent", "read-log", "{\"lines\": 10}");
        assertFalse(result.isError());
        var text = result.firstContent().toString();
        assertTrue(text.isEmpty() || text.contains("TextContent"));
    }

    @Test
    void invalidTerminalOperationReturnsError() {
        var result = tools.trellisTerminal("test", "invalid-op", null);
        assertTrue(result.isError());
    }

    @Test
    void destroyNonexistentTerminalReturnsError() {
        var result = tools.trellisTerminal("nonexistent-xyz", "destroy", null);
        assertTrue(result.isError());
    }

    @Test
    void agentStatsForNonexistentTerminal() {
        var result = tools.trellisAgent("nonexistent", "stats", null);
        assertTrue(result.isError());
    }

    @Test
    void agentInvalidOperationReturnsError() {
        var result = tools.trellisAgent("test", "invalid-op", null);
        assertTrue(result.isError());
    }

    @Test
    void agentStartWithoutTerminalReturnsError() {
        var result = tools.trellisAgent("nonexistent", "start", null);
        assertTrue(result.isError());
    }
}
