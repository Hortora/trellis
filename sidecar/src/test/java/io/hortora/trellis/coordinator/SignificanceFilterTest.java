package io.hortora.trellis.coordinator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignificanceFilterTest {

    private final SignificanceFilter filter = new SignificanceFilter();

    @Test
    void analysisWithUnblockedIsSignificant() {
        var events = List.<CoordinatorEvent>of(
                new CoordinatorEvent.AnalysisEvent(Instant.now(), "k", "owner/repo#1", List.of("owner/repo#5")));
        assertTrue(filter.isSignificant(events));
    }

    @Test
    void analysisWithEmptyUnblockedIsNotSignificant() {
        var events = List.<CoordinatorEvent>of(
                new CoordinatorEvent.AnalysisEvent(Instant.now(), "k", "ref", List.of()));
        assertFalse(filter.isSignificant(events));
    }

    @Test
    void singleWorkspaceChangeIsNotSignificant() {
        var events = List.<CoordinatorEvent>of(
                new CoordinatorEvent.WorkspaceChangedEvent(Instant.now(), "ws", Path.of("/tmp")));
        assertFalse(filter.isSignificant(events));
    }

    @Test
    void emptyBatchIsNotSignificant() {
        assertFalse(filter.isSignificant(List.of()));
    }

    @Test
    void issueEventAloneIsNotSignificant() {
        var events = List.<CoordinatorEvent>of(
                new CoordinatorEvent.IssueEvent(Instant.now(), "k", "owner/repo#1", "cache-refreshed"));
        assertFalse(filter.isSignificant(events));
    }

    @Test
    void mixedBatchWithUnblockedIsSignificant() {
        var events = List.<CoordinatorEvent>of(
                new CoordinatorEvent.WorkspaceChangedEvent(Instant.now(), "ws", Path.of("/tmp")),
                new CoordinatorEvent.AnalysisEvent(Instant.now(), "k", "ref", List.of("owner/repo#3")),
                new CoordinatorEvent.IssueEvent(Instant.now(), "k", "owner/repo#1", "cache-refreshed"));
        assertTrue(filter.isSignificant(events));
    }

    @Test
    void actionEventsUnderThresholdAreSignificant() {
        var events = List.<CoordinatorEvent>of(
                new CoordinatorEvent.ActionStateChangedEvent(
                        Instant.now(), "ws", "a1",
                        ActionStatus.PROPOSED, ActionStatus.APPROVED, "epic.next"));
        assertTrue(filter.isSignificant(events));
    }

    @Test
    void circuitBreakerBlocksActionOnlyBatchOverThreshold() {
        var events = new java.util.ArrayList<CoordinatorEvent>();
        for (int i = 0; i < 6; i++) {
            events.add(new CoordinatorEvent.ActionStateChangedEvent(
                    Instant.now(), "ws", "action-" + i,
                    ActionStatus.PROPOSED, ActionStatus.APPROVED, "slot.merge"));
        }
        assertFalse(filter.isSignificant(events));
    }

    @Test
    void terminalActionEventsExcludedFromCount() {
        var events = List.<CoordinatorEvent>of(
                new CoordinatorEvent.ActionStateChangedEvent(
                        Instant.now(), "ws", "a1",
                        ActionStatus.PROPOSED, ActionStatus.EXPIRED, "slot.merge"));
        assertFalse(filter.isSignificant(events));
    }

    @Test
    void lifecycleOperationEventIsSignificant() {
        var events = List.<CoordinatorEvent>of(
                new CoordinatorEvent.LifecycleOperationEvent(
                        Instant.now(), "ws", "slotMerge", true, "merged"));
        assertTrue(filter.isSignificant(events));
    }

    @Test
    void mixedBatchWithAnalysisAndActionsIsSignificant() {
        var events = List.<CoordinatorEvent>of(
                new CoordinatorEvent.AnalysisEvent(Instant.now(), "k", "ref", List.of("owner/repo#5")),
                new CoordinatorEvent.ActionStateChangedEvent(
                        Instant.now(), "ws", "a1",
                        ActionStatus.PROPOSED, ActionStatus.APPROVED, "epic.next"));
        assertTrue(filter.isSignificant(events));
    }
}
