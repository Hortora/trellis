package io.hortora.trellis.mcp;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ModelProviderTest {

    @Inject
    TerminalModelProvider terminalProvider;

    @Inject
    GenerationCounter counter;

    @Test
    void terminalProviderDomainIsTerminals() {
        assertEquals("terminals", terminalProvider.domain());
    }

    @Test
    @SuppressWarnings("unchecked")
    void terminalProviderSummaryReturnsList() {
        var summary = terminalProvider.summary();
        assertNotNull(summary);
        assertInstanceOf(List.class, summary);
    }

    @Test
    void terminalProviderResolveNullReturnsSummary() {
        var result = terminalProvider.resolve(null);
        assertNotNull(result);
    }

    @Test
    void terminalProviderResolveUnknownReturnsNull() {
        var result = terminalProvider.resolve("nonexistent-terminal");
        assertNull(result);
    }

    @Test
    void terminalProviderActionsIncludeBackendActions() {
        var actions = terminalProvider.actionsFor("terminal");
        assertFalse(actions.isEmpty());
        assertTrue(actions.stream().anyMatch(a -> a.name().equals("send-input")));
        assertTrue(actions.stream().anyMatch(a -> a.name().equals("read-log")));
        assertTrue(actions.stream().anyMatch(a -> a.name().equals("start-agent")));
        assertTrue(actions.stream().anyMatch(a -> a.name().equals("graceful-shutdown-agent")));
        assertTrue(actions.stream().allMatch(a -> a.source().equals("backend")));
    }

    @Test
    void terminalProviderActionsForUnknownTypeIsEmpty() {
        var actions = terminalProvider.actionsFor("unknown");
        assertTrue(actions.isEmpty());
    }

    @Test
    void generationCounterStartsAtZero() {
        assertTrue(counter.current() >= 0);
    }

    @Test
    void generationCounterIncrements() {
        long before = counter.current();
        long after = counter.increment();
        assertEquals(before + 1, after);
        assertEquals(after, counter.current());
    }

    @Test
    void actionDescriptorBackendFactory() {
        var action = ActionDescriptor.backend("test-action", "A test", "trellis_terminal", "test");
        assertEquals("test-action", action.name());
        assertEquals("A test", action.description());
        assertEquals("backend", action.source());
        assertEquals("trellis_terminal", action.tool());
        assertEquals("test", action.operation());
    }
}
