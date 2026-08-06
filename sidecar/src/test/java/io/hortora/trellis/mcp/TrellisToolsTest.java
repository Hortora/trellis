package io.hortora.trellis.mcp;

import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TrellisToolsTest {

    @Inject
    TrellisTools tools;

    @Test
    void toolsBeanIsInjectable() {
        assertNotNull(tools);
    }

    @Test
    void modelToolReturnsResponse() {
        var result = tools.trellisModel(null);
        assertNotNull(result);
        assertFalse(result.isError());
    }

    @Test
    void navigateToolReturnsResponse() {
        var result = tools.trellisNavigate("dock-bar/workspace");
        assertNotNull(result);
    }

    @Test
    void terminalToolReturnsResponse() {
        var result = tools.trellisTerminal("test", "read-log", null);
        assertNotNull(result);
    }

    @Test
    void agentToolReturnsResponse() {
        var result = tools.trellisAgent("test", "stats", null);
        assertNotNull(result);
    }

    @Test
    void lifecycleToolReturnsResponse() {
        var result = tools.trellisLifecycle("start", null);
        assertNotNull(result);
    }

    @Test
    void workspaceToolReturnsResponse() {
        var result = tools.trellisWorkspace(null, null);
        assertNotNull(result);
    }
}
