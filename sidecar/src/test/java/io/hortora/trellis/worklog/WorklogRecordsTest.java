package io.hortora.trellis.worklog;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorklogRecordsTest {

    @Test
    void worklogEventFieldsAccessible() {
        var event = new WorklogEvent(1, "2026-08-11T10:00:00Z", "work-start",
                42L, null, "/path/to/repo", "{\"key\":\"value\"}");
        assertEquals(1, event.id());
        assertEquals("work-start", event.eventType());
        assertNull(event.slotId());
    }

    @Test
    void workItemIncludesIssues() {
        var issues = List.of(
                new WorkItemIssue(42, "Hortora/trellis", true),
                new WorkItemIssue(43, "Hortora/trellis", false));
        var item = new WorkItem(1, "issue-42-worklog", "active", "primary",
                null, "2026-08-11T10:00:00Z", "/path", "Hortora/trellis", issues);
        assertEquals(2, item.issues().size());
        assertTrue(item.issues().get(0).isPrimary());
    }

    @Test
    void planStatePositionTracking() {
        var plan = new PlanState("#42", 2, 6);
        assertEquals("#42", plan.activeIssue());
        assertEquals(2, plan.completed());
        assertEquals(6, plan.total());
    }

    @Test
    void slotInfoFieldsAccessible() {
        var slot = new SlotInfo(1, 3, "/family/root", "active",
                "2026-08-11T10:00:00Z", null);
        assertEquals(3, slot.slotNumber());
        assertEquals("active", slot.state());
        assertNull(slot.archivedAt());
    }
}
