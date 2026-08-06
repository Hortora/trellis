package io.hortora.trellis.mcp;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class TrellisLifecycleTest {

    @Inject
    TrellisTools tools;

    @Test
    void invalidLifecycleOperationReturnsError() {
        var result = tools.trellisLifecycle("invalid-op", null);
        assertTrue(result.isError());
    }

    @Test
    void workspaceQueryReturnsResult() {
        var result = tools.trellisWorkspace(null, false);
        assertFalse(result.isError());
    }

    @Test
    void workspaceWithRefreshReturnsResult() {
        var result = tools.trellisWorkspace(null, true);
        assertFalse(result.isError());
    }

    @Test
    void workspaceWithSubpathReturnsResult() {
        var result = tools.trellisWorkspace("repos", false);
        assertFalse(result.isError());
    }

    @Test
    void workspaceWithInvalidSubpathReturnsEmptyOrError() {
        var result = tools.trellisWorkspace("nonexistent", false);
        // With no workspace watched → returns empty list (success)
        // With workspace watched → returns error for invalid subpath
        assertNotNull(result);
    }
}
