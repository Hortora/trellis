package io.hortora.trellis.coordinator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CountdownSchedulerTest {

    CountdownScheduler scheduler = new CountdownScheduler();

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void scheduleFiresCallback() throws Exception {
        var latch = new CountDownLatch(1);
        var received = new AtomicReference<String>();
        scheduler.schedule("a1", 1, id -> {
            received.set(id);
            latch.countDown();
        });
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals("a1", received.get());
    }

    @Test
    void cancelPreventsCallback() throws Exception {
        var latch = new CountDownLatch(1);
        scheduler.schedule("a1", 5, id -> latch.countDown());
        scheduler.cancel("a1");
        assertFalse(latch.await(2, TimeUnit.SECONDS));
    }

    @Test
    void cancelAllClearsAllPending() throws Exception {
        var latch = new CountDownLatch(3);
        scheduler.schedule("a1", 5, id -> latch.countDown());
        scheduler.schedule("a2", 5, id -> latch.countDown());
        scheduler.schedule("a3", 5, id -> latch.countDown());
        scheduler.cancelAll();
        assertFalse(latch.await(2, TimeUnit.SECONDS));
        assertFalse(scheduler.hasCountdown("a1"));
        assertFalse(scheduler.hasCountdown("a2"));
        assertFalse(scheduler.hasCountdown("a3"));
    }

    @Test
    void deadlineReturnsCorrectTime() {
        scheduler.schedule("a1", 30, id -> {});
        var deadline = scheduler.deadline("a1");
        assertNotNull(deadline);
        var diff = deadline.getEpochSecond() - Instant.now().getEpochSecond();
        assertTrue(diff >= 28 && diff <= 31, "Expected ~30s from now, got " + diff);
    }

    @Test
    void hasCountdownReturnsTrueWhilePending() {
        scheduler.schedule("a1", 30, id -> {});
        assertTrue(scheduler.hasCountdown("a1"));
        scheduler.cancel("a1");
        assertFalse(scheduler.hasCountdown("a1"));
    }

    @Test
    void deadlineReturnsNullForUnknownAction() {
        assertNull(scheduler.deadline("unknown"));
    }

    @Test
    void exceptionInCallbackDoesNotKillScheduler() throws Exception {
        var latch = new CountDownLatch(1);
        scheduler.schedule("a1", 1, id -> { throw new RuntimeException("boom"); });
        scheduler.schedule("a2", 2, id -> latch.countDown());
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void rescheduleReplacesExistingCountdown() throws Exception {
        var latch = new CountDownLatch(1);
        var received = new AtomicReference<String>();
        scheduler.schedule("a1", 10, id -> {}); // original — long timer
        scheduler.schedule("a1", 1, id -> {      // replacement — short timer
            received.set(id);
            latch.countDown();
        });
        assertTrue(latch.await(3, TimeUnit.SECONDS));
        assertEquals("a1", received.get());
    }
}
