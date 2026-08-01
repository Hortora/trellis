package io.hortora.trellis.coordinator;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
}
