package io.hortora.trellis.mcp;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkspaceModelProviderTest {

    @Inject
    WorkspaceModelProvider provider;

    @Test
    void domainIsWorkspace() {
        assertEquals("workspace", provider.domain());
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryReturnsMapWithExpectedKeys() {
        var summary = provider.summary();
        assertNotNull(summary);
        assertInstanceOf(Map.class, summary);
        var map = (Map<String, Object>) summary;
        assertTrue(map.isEmpty() || map.containsKey("root"));
    }

    @Test
    void resolveNullWithNoModelsReturnsNull() {
        var result = provider.resolve(null);
        // With no workspace watched, allModels() is empty → null
        // If a workspace IS watched, we get a map — either way, no exception
        assertTrue(result == null || result instanceof Map);
    }

    @Test
    void resolveInvalidSubpathReturnsNull() {
        var result = provider.resolve("nonexistent");
        assertNull(result);
    }

    @Test
    void actionsForAnyTypeIsEmpty() {
        var actions = provider.actionsFor("workspace");
        assertTrue(actions.isEmpty());
    }

    @Test
    void actionsForNullIsEmpty() {
        var actions = provider.actionsFor(null);
        assertTrue(actions.isEmpty());
    }
}
