package io.hortora.trellis.lifecycle;

import io.hortora.trellis.scanner.WorkspaceChanged;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class LifecycleManager {

    private static final Logger LOG = Logger.getLogger(LifecycleManager.class);

    @Inject
    ScriptRunner scriptRunner;

    @Inject
    @WorkspaceChanged
    Event<Path> workspaceChanged;

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public OperationResult start(Path workspaceRoot, String branch, String issue)
            throws IOException, InterruptedException, ConcurrentOperationException {
        return withLock(workspaceRoot.toString(), () -> {
            var routeResult = scriptRunner.run("work", "work_router.py",
                    List.of(branch, workspaceRoot.toString(), workspaceRoot.toString()));
            if (!routeResult.success()) return routeResult;

            var branchResult = scriptRunner.run("work-start", "branch_create.py",
                    List.of("create-branches", workspaceRoot.toString(), branch));
            if (!branchResult.success()) return branchResult;

            var scaffoldResult = scriptRunner.run("work-start", "scaffold.py",
                    List.of(workspaceRoot.toString(), issue));
            fireWorkspaceChanged(workspaceRoot);
            return scaffoldResult;
        });
    }

    public OperationResult end(String slotId, Path workspaceRoot)
            throws IOException, InterruptedException, ConcurrentOperationException {
        return withLock(workspaceRoot.toString(), () -> {
            var rebaseResult = scriptRunner.run("work-end", "land_branch.py",
                    List.of("rebase", workspaceRoot.toString()));
            if (!rebaseResult.success()) return rebaseResult;

            var pushResult = scriptRunner.run("work-end", "land_branch.py",
                    List.of("push", workspaceRoot.toString()));
            if (!pushResult.success()) return pushResult;

            var stampResult = scriptRunner.run("work-end", "land_branch.py",
                    List.of("stamp", workspaceRoot.toString()));
            fireWorkspaceChanged(workspaceRoot);
            return stampResult;
        });
    }

    public OperationResult pause(String slotId, Path workspaceRoot)
            throws IOException, InterruptedException, ConcurrentOperationException {
        return withLock(workspaceRoot.toString(), () -> {
            var wipResult = scriptRunner.run("work-pause", "pause_exec.py",
                    List.of("commit-wip", workspaceRoot.toString()));
            if (!wipResult.success()) return wipResult;

            var stackResult = scriptRunner.run("work-pause", "pause_exec.py",
                    List.of("push-and-stack", workspaceRoot.toString()));
            fireWorkspaceChanged(workspaceRoot);
            return stackResult;
        });
    }

    public OperationResult resume(String slotId, Path workspaceRoot)
            throws IOException, InterruptedException, ConcurrentOperationException {
        return withLock(workspaceRoot.toString(), () -> {
            var checkoutResult = scriptRunner.run("work-resume", "resume_exec.py",
                    List.of("checkout-branches", workspaceRoot.toString()));
            if (!checkoutResult.success()) return checkoutResult;

            var rebaseResult = scriptRunner.run("work-resume", "resume_exec.py",
                    List.of("rebase", workspaceRoot.toString()));
            if (!rebaseResult.success()) return rebaseResult;

            var resetResult = scriptRunner.run("work-resume", "resume_exec.py",
                    List.of("reset-wip", workspaceRoot.toString()));
            fireWorkspaceChanged(workspaceRoot);
            return resetResult;
        });
    }

    public OperationResult slotCreate(Path workspaceRoot, List<String> args)
            throws IOException, InterruptedException, ConcurrentOperationException {
        return withLock(workspaceRoot.toString(), () -> {
            var result = scriptRunner.run("work-slot", "slot_manager.py",
                    prepend("create-slot", args));
            fireWorkspaceChanged(workspaceRoot);
            return result;
        });
    }

    public OperationResult slotMerge(String slotId, Path workspaceRoot)
            throws IOException, InterruptedException, ConcurrentOperationException {
        return withLock(workspaceRoot.toString(), () -> {
            var result = scriptRunner.run("work-slot", "slot_manager.py",
                    List.of("merge-slot", slotId));
            fireWorkspaceChanged(workspaceRoot);
            return result;
        });
    }

    public OperationResult epicSetup(Path workspaceRoot, List<String> args)
            throws IOException, InterruptedException, ConcurrentOperationException {
        return withLock(workspaceRoot.toString(), () -> {
            var result = scriptRunner.run("work-slot", "epic_manager.py",
                    prepend("write", args));
            fireWorkspaceChanged(workspaceRoot);
            return result;
        });
    }

    public OperationResult epicNext(String epicPath)
            throws IOException, InterruptedException, ConcurrentOperationException {
        return withLock("epic-next", () ->
                scriptRunner.run("work-slot", "epic_manager.py",
                        List.of("advance", epicPath)));
    }

    boolean tryLock(String key) {
        var lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        return lock.tryLock();
    }

    void unlock(String key) {
        var lock = locks.get(key);
        if (lock != null) lock.unlock();
    }

    private OperationResult withLock(String key, LockedOperation operation)
            throws IOException, InterruptedException, ConcurrentOperationException {
        if (!tryLock(key)) {
            throw new ConcurrentOperationException("Operation already in progress for: " + key);
        }
        try {
            return operation.execute();
        } finally {
            unlock(key);
        }
    }

    private void fireWorkspaceChanged(Path root) {
        try {
            workspaceChanged.fire(root);
        } catch (Exception e) {
            LOG.debugf(e, "Failed to fire WorkspaceChanged event for %s", root);
        }
    }

    private List<String> prepend(String first, List<String> rest) {
        var result = new java.util.ArrayList<String>();
        result.add(first);
        result.addAll(rest);
        return result;
    }

    @FunctionalInterface
    interface LockedOperation {
        OperationResult execute() throws IOException, InterruptedException;
    }
}
