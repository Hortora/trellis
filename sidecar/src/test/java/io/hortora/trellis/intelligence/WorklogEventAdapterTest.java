package io.hortora.trellis.intelligence;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WorklogEventAdapterTest {

    @Test
    void parseDaysAgoCalculatesCorrectly() {
        var now = Instant.parse("2026-08-29T10:00:00Z");
        var tenDaysAgo = "2026-08-19T10:00:00Z";

        assertEquals(10, WorklogEventAdapter.parseDaysAgo(tenDaysAgo, now));
    }

    @Test
    void parseDaysAgoReturnsZeroForSameDay() {
        var now = Instant.parse("2026-08-29T10:00:00Z");

        assertEquals(0, WorklogEventAdapter.parseDaysAgo("2026-08-29T08:00:00Z", now));
    }

    @Test
    void parseDaysAgoReturnsZeroForInvalidTimestamp() {
        var now = Instant.now();

        assertEquals(0, WorklogEventAdapter.parseDaysAgo("not-a-date", now));
    }

    @Test
    void parseDaysAgoReturnsZeroForNull() {
        var now = Instant.now();

        assertEquals(0, WorklogEventAdapter.parseDaysAgo(null, now));
    }
}
