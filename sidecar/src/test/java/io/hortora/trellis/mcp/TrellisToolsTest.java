package io.hortora.trellis.mcp;

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
        var result = tools.trellisWorkspace(null, null, null, null);
        assertNotNull(result);
        assertFalse(result.isError());
    }

    @Test
    void workspaceOperationWithNoFrontendReturnsError() {
        var result = tools.trellisWorkspace(null, null, "frame-create", "{\"tabs\":[]}");
        assertNotNull(result);
        assertTrue(result.isError());
    }

    @Test
    void workspaceReadPathUnchangedWithOperationNull() {
        var result = tools.trellisWorkspace(null, null, null, null);
        assertNotNull(result);
        assertFalse(result.isError());
    }

    @Test
    void workspaceOperationWithNullParamsDoesNotThrow() {
        var result = tools.trellisWorkspace(null, null, "frame-pin", null);
        assertNotNull(result);
        assertTrue(result.isError());
    }

    @Test
    void scanRootRequiresRootParam() {
        var result = tools.trellisWorkspace(null, null, "scan-root", "{}");
        assertNotNull(result);
        assertTrue(result.isError());
    }

    @Test
    void scanRootRejectsNonExistentDirectory() {
        var result = tools.trellisWorkspace(null, null, "scan-root",
                "{\"root\":\"/nonexistent/path/that/does/not/exist\"}");
        assertNotNull(result);
        assertTrue(result.isError());
    }

    @Test
    void scanRootSucceedsForValidDirectory() {
        var result = tools.trellisWorkspace(null, null, "scan-root",
                "{\"root\":\"/tmp\"}");
        assertNotNull(result);
        assertFalse(result.isError());
    }

}
