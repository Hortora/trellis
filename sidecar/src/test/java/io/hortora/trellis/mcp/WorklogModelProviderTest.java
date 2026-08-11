package io.hortora.trellis.mcp;

import io.hortora.trellis.worklog.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorklogModelProviderTest {

    private WorklogModelProvider provider;

    @BeforeEach
    void setUp() {
        var stubService = new StubWorklogService();
        provider = new WorklogModelProvider(stubService, null);
    }

    @Test
    void domainIsWorklog() {
        assertEquals("worklog", provider.domain());
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryReturnsSummaryMap() {
        var result = provider.summary();
        assertNotNull(result);
        assertInstanceOf(Map.class, result);
        var map = (Map<String, Object>) result;
        assertEquals(0, map.get("activeWorkItems"));
        assertEquals(0, map.get("recentEventCount"));
        assertEquals(0, map.get("slotsActive"));
    }

    @Test
    void resolveEventsReturnsList() {
        assertInstanceOf(List.class, provider.resolve("events"));
    }

    @Test
    void resolveWorkItemsReturnsList() {
        assertInstanceOf(List.class, provider.resolve("work-items"));
    }

    @Test
    void resolveSlotsReturnsList() {
        assertInstanceOf(List.class, provider.resolve("slots"));
    }

    @Test
    void resolveBacklogReturnsList() {
        assertInstanceOf(List.class, provider.resolve("backlog"));
    }

    @Test
    void resolveUnknownReturnsNull() {
        assertNull(provider.resolve("nonexistent"));
    }

    @Test
    void resolveEmptyReturnsSummary() {
        assertInstanceOf(Map.class, provider.resolve(""));
    }

    @Test
    void actionsForReturnsEmpty() {
        assertTrue(provider.actionsFor("worklog").isEmpty());
    }

    static class StubWorklogService extends WorklogService {
        StubWorklogService() {
            super(null, new GenerationCounter(), (Path) null);
        }

        @Override public boolean isDbAvailable() { return false; }
        @Override public List<WorklogEvent> recentEvents(String s, String t, int l) { return List.of(); }
        @Override public List<WorkItem> activeWork() { return List.of(); }
        @Override public List<SlotInfo> slotStatus(String f) { return List.of(); }
        @Override public List<BacklogEntry> backlogEntries(String r) { return List.of(); }
        @Override public List<WorklogEvent> workItemTimeline(String b, String r) { return List.of(); }
        @Override public PlanState planPosition(Path p) { return null; }
        @Override public WorklogSummary summary(Path p) {
            return new WorklogSummary(0, 0, null, null, 0);
        }
    }
}
