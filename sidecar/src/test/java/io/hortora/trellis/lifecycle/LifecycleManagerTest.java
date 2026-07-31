package io.hortora.trellis.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleManagerTest {

    LifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = new LifecycleManager();
    }

    @Test
    void lockPreventsSecondAcquisitionFromDifferentThread() throws Exception {
        assertTrue(manager.tryLock("slot-1"));

        var result = java.util.concurrent.Executors.newSingleThreadExecutor()
                                                   .submit(() -> manager.tryLock("slot-1")).get();

        assertFalse(result);
        manager.unlock("slot-1");
    }

    @Test
    void unlockAllowsReacquisition() {
        assertTrue(manager.tryLock("slot-1"));
        manager.unlock("slot-1");
        assertTrue(manager.tryLock("slot-1"));
        manager.unlock("slot-1");
    }

    @Test
    void differentKeysDoNotConflict() {
        assertTrue(manager.tryLock("slot-1"));
        assertTrue(manager.tryLock("slot-2"));
        manager.unlock("slot-1");
        manager.unlock("slot-2");
    }

    @Test
    void sameWorkspaceOperationsConflict() throws Exception {
        assertTrue(manager.tryLock("/workspace/a"));

        var result = java.util.concurrent.Executors.newSingleThreadExecutor()
                .submit(() -> manager.tryLock("/workspace/a")).get();

        assertFalse(result);
        manager.unlock("/workspace/a");
    }

    @Test
    void differentWorkspacesDoNotConflict() throws Exception {
        assertTrue(manager.tryLock("/workspace/a"));

        var exec = java.util.concurrent.Executors.newSingleThreadExecutor();
        var locked = exec.submit(() -> manager.tryLock("/workspace/b")).get();
        assertTrue(locked);
        exec.submit(() -> manager.unlock("/workspace/b")).get();

        manager.unlock("/workspace/a");
    }
}
