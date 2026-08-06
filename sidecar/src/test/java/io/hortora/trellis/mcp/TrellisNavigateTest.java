package io.hortora.trellis.mcp;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TrellisNavigateTest {

    @Inject
    TrellisTools tools;

    @Inject
    UIStateStore store;

    @Test
    void navigateWithNoFrontendReturnsError() {
        store.clear();
        var result = tools.trellisNavigate("dock-bar/workspace");
        assertTrue(result.isError());
        var text = result.firstContent().toString();
        assertTrue(text.contains("no frontend"));
    }

    @Test
    void navigateWithRecentFrontendEmitsEvent() {
        store.update(Map.of("activePanel", "dashboard", "panels", Map.of()));
        var result = tools.trellisNavigate("dock-bar/workspace");
        // Will timeout (no real frontend to acknowledge) but should not be "no frontend"
        assertTrue(result.isError());
        var text = result.firstContent().toString();
        assertTrue(text.contains("timeout"));
    }

    @Test
    void correlationRegistrationAndAcknowledgment() {
        var future = store.registerNavigation("test-corr-1");
        assertFalse(future.isDone());
        store.acknowledgeNavigation("test-corr-1", Map.of("activePanel", "workspace"));
        assertTrue(future.isDone());
        assertEquals("workspace", future.join().get("activePanel"));
    }

    @Test
    void acknowledgeUnknownCorrelationIsIgnored() {
        store.acknowledgeNavigation("unknown-corr-id", Map.of("activePanel", "test"));
        // No exception — silently ignored
    }
}
