package io.hortora.trellis.coordinator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OptimisticLockTest {

    @TempDir Path tmpDir;

    DataSource dataSource;
    ActionService service;

    @BeforeEach
    void setUp() throws Exception {
        var sds = new SQLiteDataSource();
        sds.setUrl("jdbc:sqlite:" + tmpDir.resolve("test-" + System.nanoTime() + ".db"));
        dataSource = sds;
        new CoordinatorSchemaManager().initialize(dataSource);

        var advisoryExecutor = new AdvisoryActionExecutor();
        service = ActionService.forTest(dataSource, List.of(advisoryExecutor));
    }

    @Test
    void casSucceedsWhenStatusMatches() {
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "ready", "/ws");
        int updated = service.updateStatusCas(action.id(), ActionStatus.PROPOSED, ActionStatus.APPROVED);
        assertEquals(1, updated);
        var fetched = service.getAction(action.id());
        assertEquals(ActionStatus.APPROVED, fetched.status());
    }

    @Test
    void casFailsWhenStatusMismatch() {
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "ready", "/ws");
        service.reject(action.id());

        int updated = service.updateStatusCas(action.id(), ActionStatus.PROPOSED, ActionStatus.APPROVED);
        assertEquals(0, updated);
        var fetched = service.getAction(action.id());
        assertEquals(ActionStatus.REJECTED, fetched.status());
    }

    @Test
    void concurrentCasOnlyOneWins() throws Exception {
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "ready", "/ws");
        var latch = new CountDownLatch(1);
        var wins = new AtomicInteger(0);

        var t1 = Thread.ofVirtual().start(() -> {
            try { latch.await(); } catch (InterruptedException ignored) {}
            if (service.updateStatusCas(action.id(), ActionStatus.PROPOSED, ActionStatus.APPROVED) == 1)
                wins.incrementAndGet();
        });
        var t2 = Thread.ofVirtual().start(() -> {
            try { latch.await(); } catch (InterruptedException ignored) {}
            if (service.updateStatusCas(action.id(), ActionStatus.PROPOSED, ActionStatus.REJECTED) == 1)
                wins.incrementAndGet();
        });

        latch.countDown();
        t1.join(5000);
        t2.join(5000);

        assertEquals(1, wins.get());
    }

    @Test
    void casUpdatesResolvedAtForTerminalStates() {
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "ready", "/ws");
        assertNull(service.getAction(action.id()).resolvedAt());

        service.updateStatusCas(action.id(), ActionStatus.PROPOSED, ActionStatus.REJECTED);
        var fetched = service.getAction(action.id());
        assertNotNull(fetched.resolvedAt());
    }

    @Test
    void casDoesNotSetResolvedAtForNonTerminalStates() {
        var action = service.propose("adv1", ActionCategory.ADVISORY, "advisory.prioritise",
                Map.of("issueKey", "#5"), "ready", "/ws");
        service.updateStatusCas(action.id(), ActionStatus.PROPOSED, ActionStatus.APPROVED);
        var fetched = service.getAction(action.id());
        assertNull(fetched.resolvedAt());
    }
}
