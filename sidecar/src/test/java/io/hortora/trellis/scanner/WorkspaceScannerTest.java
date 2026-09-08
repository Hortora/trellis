package io.hortora.trellis.scanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceScannerTest {

    @TempDir
    Path root;

    WorkspaceScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new WorkspaceScanner();
    }

    // --- Repo discovery ---

    @Test
    void scanFindsReposWithGitDirectory() throws IOException {
        createRepo("engine");
        createRepo("platform");

        var model = scanner.scan(root);

        assertEquals(2, model.repos().size());
        var names = model.repos().stream().map(RepoInfo::name).sorted().toList();
        assertEquals(List.of("engine", "platform"), names);
    }

    @Test
    void scanIgnoresDirectoriesWithoutGit() throws IOException {
        createRepo("engine");
        Files.createDirectories(root.resolve("docs"));

        var model = scanner.scan(root);

        assertEquals(1, model.repos().size());
        assertEquals("engine", model.repos().getFirst().name());
    }

    @Test
    void scanReadsCurrentBranch() throws IOException {
        createRepoWithBranch("engine", "issue-42-feature");

        var model = scanner.scan(root);

        assertEquals("issue-42-feature", model.repos().getFirst().branch());
    }

    @Test
    void scanReadsRemoteUrl() throws IOException {
        var repoPath = createRepo("engine");
        writeGitConfig(repoPath, "git@github.com:casehubio/engine.git");

        var model = scanner.scan(root);

        assertEquals("git@github.com:casehubio/engine.git", model.repos().getFirst().remoteUrl());
    }

    @Test
    void scanSkipsWorktreesDirectory() throws IOException {
        createRepo("engine");
        Files.createDirectories(root.resolve("worktrees/1/engine/.git"));

        var model = scanner.scan(root);

        assertEquals(1, model.repos().size());
        assertEquals("engine", model.repos().getFirst().name());
    }

    @Test
    void scanFindsArchivedSlotsInAttic() throws IOException {
        createSlot(6, """
                # Slot 6

                ## Issue
                org/repo#14

                ## Repos
                - engine
                """);
        createArchivedSlot(3, """
                # Slot 3

                ## Issue
                org/repo#9

                ## Repos
                - pages
                """);
        createArchivedSlot(5, """
                # Slot 5

                ## Issue
                org/repo#11

                ## Repos
                - ledger
                """);

        var model = scanner.scan(root);

        assertEquals(3, model.slots().size());
        var active = model.slots().stream().filter(s -> s.status() == SlotStatus.ACTIVE).toList();
        var archived = model.slots().stream().filter(s -> s.status() == SlotStatus.ARCHIVED).toList();
        assertEquals(1, active.size());
        assertEquals(2, archived.size());
        assertEquals(6, active.getFirst().number());
        var archivedNumbers = archived.stream().map(SlotInfo::number).sorted().toList();
        assertEquals(List.of(3, 5), archivedNumbers);
    }

    // --- Slot discovery ---

    @Test
    void scanFindsSlots() throws IOException {
        createSlot(2, """
                # Slot 2 — issue-120-trellis
                
                ## Issue
                Hortora/soredium#120
                Covers:
                Type: epic
                
                ## Repos
                - soredium (primary)
                """);

        var model = scanner.scan(root);

        assertEquals(1, model.slots().size());
        var slot = model.slots().getFirst();
        assertEquals(2, slot.number());
        assertEquals("Hortora/soredium#120", slot.issue());
        assertTrue(slot.isEpic());
        assertEquals(SlotStatus.ACTIVE, slot.status());
        assertEquals(List.of("soredium"), slot.repos());
    }

    @Test
    void scanDetectsReadySlot() throws IOException {
        createSlot(45, """
                # Slot 45 — issue-100-feature
                
                ## Issue
                casehubio/blocks-ui#100
                Covers: 100
                
                ## State
                state: ready
                
                ## Repos
                - blocks-ui (primary)
                - chat-app
                """);

        var model = scanner.scan(root);

        assertEquals(SlotStatus.READY, model.slots().getFirst().status());
        assertFalse(model.slots().getFirst().isEpic());
        assertEquals(List.of("blocks-ui", "chat-app"), model.slots().getFirst().repos());
    }

    @Test
    void scanSkipsSlotDirectoriesWithoutSlotFile() throws IOException {
        Files.createDirectories(root.resolve("slots/99"));

        var model = scanner.scan(root);

        assertTrue(model.slots().isEmpty());
    }

    @Test
    void scanHandlesCorruptedSlotFile() throws IOException {
        createSlot(5, "this is not valid slot content at all {{{{");

        var model = scanner.scan(root);

        assertTrue(model.slots().isEmpty());
    }

    // --- Pause stack discovery ---

    @Test
    void scanFindsPauseEntries() throws IOException {
        var workDir = createSlotWorkspace(2);
        Files.writeString(workDir.resolve("design/.pause-stack"), """
                - branch: issue-377-deep-dives
                  issue: 377
                  paused: 2026-07-20T03:12:19Z
                """);

        var model = scanner.scan(root);

        assertEquals(1, model.pauses().size());
        var pause = model.pauses().getFirst();
        assertEquals("issue-377-deep-dives", pause.branch());
        assertEquals(377, pause.issue());
    }

    @Test
    void scanFindsMultiplePauseEntries() throws IOException {
        var workDir = createSlotWorkspace(3);
        Files.writeString(workDir.resolve("design/.pause-stack"), """
                - branch: issue-100-first
                  issue: 100
                  paused: 2026-07-19T10:00:00Z
                - branch: issue-200-second
                  issue: 200
                  paused: 2026-07-20T11:00:00Z
                """);

        var model = scanner.scan(root);

        assertEquals(2, model.pauses().size());
    }

    @Test
    void scanIgnoresEmptyPauseStack() throws IOException {
        var workDir = createSlotWorkspace(2);
        Files.writeString(workDir.resolve("design/.pause-stack"), "");

        var model = scanner.scan(root);

        assertTrue(model.pauses().isEmpty());
    }

    // --- Epic discovery ---

    @Test
    void scanFindsEpicFiles() throws IOException {
        var workDir = createSlotWorkspace(2);
        Files.writeString(workDir.resolve("design/.epic"), """
                # Epic #210 — acl-completion
                
                ## Issue
                casehubio/platform#210
                Covers:
                Type: epic
                
                ## Batch Plan
                
                ### Batch 1 — S-batch ← current
                - [x] #211 — purge expired ACL entries
                - [ ] #212 — audit log retention purge ← active
                - [ ] #213 — bulk grant/revoke SPI
                
                ## Session State
                Current batch: 1
                Current issue: #212 — audit log retention purge
                """);

        var model = scanner.scan(root);

        assertEquals(1, model.epics().size());
        var epic = model.epics().getFirst();
        assertEquals("casehubio/platform#210", epic.issue());
        assertEquals(1, epic.currentBatch());
        assertEquals("#212 — audit log retention purge", epic.currentIssue());
        assertEquals(1, epic.completedChildren());
        assertEquals(3, epic.totalChildren());
    }

    @Test
    void scanCountsCompletedChildrenFromCheckedBoxes() throws IOException {
        var workDir = createSlotWorkspace(1);
        Files.writeString(workDir.resolve("design/.epic"), """
                # Epic #100
                
                ## Issue
                org/repo#100
                
                ## Batch Plan
                
                ### Batch 1
                - [x] #101 — done
                - [x] #102 — done
                - [ ] #103 — not done
                
                ### Batch 2
                - [x] #104 — done
                - [ ] #105 — not done
                
                ## Session State
                Current batch: 2
                Current issue: #105
                """);

        var model = scanner.scan(root);

        var epic = model.epics().getFirst();
        assertEquals(3, epic.completedChildren());
        assertEquals(5, epic.totalChildren());
        assertEquals(2, epic.currentBatch());
    }

    // --- Model properties ---


    @Test
    void scanParsesSlotWithPausedStatus() throws IOException {
        createSlot(1, """
                      # Slot 1
                      slug: test-slot
                      title: Test slot
                      
                      ## Issue
                      org/repo#1
                      
                      ## Status
                      status: paused
                      
                      ## Repos
                      - repo-a
                      """);

        var model = scanner.scan(root);

        assertEquals(1, model.slots().size());
        assertEquals(SlotStatus.PAUSED, model.slots().getFirst().status());
    }

    @Test
    void scanDefaultsToActiveWhenNoStatusSection() throws IOException {
        createSlot(1, """
                      # Slot 1
                      slug: test-slot
                      title: Test slot
                      
                      ## Issue
                      org/repo#1
                      
                      ## Repos
                      - repo-a
                      """);

        var model = scanner.scan(root);

        assertEquals(1, model.slots().size());
        assertEquals(SlotStatus.ACTIVE, model.slots().getFirst().status());
    }

    @Test
    void newStateFormatParsesCorrectly() throws IOException {
        createSlot(1, """
                      # Slot 1
                      slug: test-slot
                      title: Test slot
                      
                      ## Issue
                      org/repo#1
                      
                      ## State
                      state: landed
                      
                      ## Repos
                      - repo-a
                      """);

        var model = scanner.scan(root);

        assertEquals(SlotStatus.LANDED, model.slots().getFirst().status());
    }

    @Test
    void scanSetsRootAndTimestamp() throws IOException {
        var model = scanner.scan(root);

        assertEquals(root, model.root());
        assertNotNull(model.scannedAt());
    }

    @Test
    void scanReturnsEmptyModelForEmptyRoot() throws IOException {
        var model = scanner.scan(root);

        assertTrue(model.repos().isEmpty());
        assertTrue(model.slots().isEmpty());
        assertTrue(model.pauses().isEmpty());
        assertTrue(model.epics().isEmpty());
    }

    // --- Failure modes ---

    @Test
    void scanSkipsRepoWithLockedIndex() throws IOException {
        var repoPath = createRepo("engine");
        Files.createFile(repoPath.resolve(".git/index.lock"));

        var model = scanner.scan(root);

        assertTrue(model.repos().isEmpty());
    }

    // --- Helpers ---


// --- Plan parsing ---

    @Test
    void scanParsesMultiBatchPlan() throws IOException {
        createSlot(5, """
                      # Slot 5
                      slug: issue-468-throttle
                      
                      ## Issue
                      casehubio/parent#468
                      
                      ## Repos
                      - engine
                      """);
        Files.writeString(root.resolve("slots/5/.plan"),
                "# Work Plan\n\n## Queue\n"
                + "- [ ] casehubio/parent#468 — Foundation Throttle (epic)\n"
                + "  ### Batch 1 — Shared contract\n"
                + "  - [x] casehubio/engine#1043 — Concurrency throttle\n"
                + "  - [ ] casehubio/engine#1044 — Watchdog bridge ← active\n"
                + "  ### Batch 2 — Session-level\n"
                + "  - [ ] casehubio/claudony#203 — Session throttle\n");

        var model = scanner.scan(root);

        var slot = model.slots().getFirst();
        assertNotNull(slot.planProgress());
        var plan = slot.planProgress();
        assertEquals(3, plan.batches().size());
        assertNull(plan.batches().get(0).name());
        assertEquals(1, plan.batches().get(0).items().size());
        assertEquals("casehubio/parent#468", plan.batches().get(0).items().get(0).ref());
        assertEquals("Foundation Throttle", plan.batches().get(0).items().get(0).title());
        assertEquals("Batch 1 — Shared contract", plan.batches().get(1).name());
        assertEquals(2, plan.batches().get(1).items().size());
        assertTrue(plan.batches().get(1).items().get(0).done());
        assertTrue(plan.batches().get(1).items().get(1).active());
        assertEquals("Batch 2 — Session-level", plan.batches().get(2).name());
        assertEquals(1, plan.batches().get(2).items().size());
        assertEquals("casehubio/engine#1044", plan.activeIssue());
        assertEquals(1, plan.completed());
        assertEquals(3, plan.total());
    }

    @Test
    void scanParsesPlanWithNoBatches() throws IOException {
        createSlot(3, """
                      # Slot 3
                      
                      ## Issue
                      org/repo#100
                      
                      ## Repos
                      - engine
                      """);
        Files.writeString(root.resolve("slots/3/.plan"), """
                                                         # Work Plan — issue-100
                                                         
                                                         ## State
                                                         state: active
                                                         
                                                         ## Queue
                                                         - [ ] casehubio/engine#189 — Normative interop ← active
                                                         """);

        var model = scanner.scan(root);

        var plan = model.slots().getFirst().planProgress();
        assertNotNull(plan);
        assertEquals(1, plan.batches().size());
        assertNull(plan.batches().getFirst().name());
        assertEquals(1, plan.batches().getFirst().items().size());
        assertTrue(plan.batches().getFirst().items().getFirst().active());
        assertEquals(0, plan.completed());
        assertEquals(1, plan.total());
    }

    @Test
    void scanReturnsNullPlanWhenNoPlanFile() throws IOException {
        createSlot(7, """
                      # Slot 7
                      
                      ## Issue
                      org/repo#50
                      
                      ## Repos
                      - engine
                      """);

        var model = scanner.scan(root);

        assertNull(model.slots().getFirst().planProgress());
    }

    @Test
    void scanParsesPlanWithAllItemsDone() throws IOException {
        createSlot(4, """
                      # Slot 4
                      
                      ## Issue
                      org/repo#30
                      
                      ## Repos
                      - engine
                      """);
        Files.writeString(root.resolve("slots/4/.plan"), """
                                                         # Work Plan
                                                         
                                                         ## Queue
                                                         - [x] org/repo#31 — First
                                                         - [x] org/repo#32 — Second
                                                         """);

        var model = scanner.scan(root);

        var plan = model.slots().getFirst().planProgress();
        assertNotNull(plan);
        assertNull(plan.activeIssue());
        assertEquals(2, plan.completed());
        assertEquals(2, plan.total());
    }

    @Test
    void scanParsesNestedEpicWithoutBatches() throws IOException {
        createSlot(8, """
                      # Slot 8

                      ## Issue
                      org/repo#100

                      ## Repos
                      - engine
                      """);
        Files.writeString(root.resolve("slots/8/.plan"), """
                # Work Plan

                ## Queue
                - [ ] org/repo#100 — Epic A (epic)
                  - [x] org/repo#200 — First task
                  - [ ] org/repo#201 — Second task ← active
                - [ ] org/repo#101 — Epic B (epic)
                  - [ ] org/repo#202 — Third task
                  - [ ] org/repo#203 — Fourth task
                """);

        var model = scanner.scan(root);

        var plan = model.slots().getFirst().planProgress();
        assertNotNull(plan);
        assertEquals(1, plan.batches().size());
        assertNull(plan.batches().getFirst().name());
        var items = plan.batches().getFirst().items();
        assertEquals(2, items.size());
        assertEquals("org/repo#100", items.get(0).ref());
        assertEquals("Epic A", items.get(0).title());
        assertEquals(2, items.get(0).children().size());
        assertTrue(items.get(0).children().get(0).done());
        assertEquals("org/repo#200", items.get(0).children().get(0).ref());
        assertTrue(items.get(0).children().get(1).active());
        assertEquals("org/repo#101", items.get(1).ref());
        assertEquals(2, items.get(1).children().size());
        assertEquals("org/repo#201", plan.activeIssue());
        assertEquals(1, plan.completed());
        assertEquals(4, plan.total());
    }

    @Test
    void scanFindsPlanInWorkspaceSubdirectory() throws IOException {
        createSlot(9, """
                      # Slot 9

                      ## Issue
                      org/repo#200

                      ## Repos
                      - platform
                      """);
        Path wspDir = root.resolve("slots/9/wsp-" + root.getFileName() + "-platform");
        Files.createDirectories(wspDir);
        Files.writeString(wspDir.resolve(".plan"),
                "# Work Plan\n\n## Queue\n"
                + "- [ ] org/repo#200 — Some feature ← active\n");

        var model = scanner.scan(root);

        var plan = model.slots().getFirst().planProgress();
        assertNotNull(plan);
        assertEquals(1, plan.batches().size());
        assertEquals("org/repo#200", plan.activeIssue());
    }

    private Path createRepo(String name) throws IOException {
        var repoPath = root.resolve(name);
        Files.createDirectories(repoPath.resolve(".git/refs/heads"));
        Files.writeString(repoPath.resolve(".git/HEAD"), "ref: refs/heads/main\n");
        return repoPath;
    }

    private Path createRepoWithBranch(String name, String branch) throws IOException {
        var repoPath = createRepo(name);
        Files.writeString(repoPath.resolve(".git/HEAD"), "ref: refs/heads/" + branch + "\n");
        return repoPath;
    }

    private void writeGitConfig(Path repoPath, String remoteUrl) throws IOException {
        Files.writeString(repoPath.resolve(".git/config"), """
                [remote "origin"]
                \turl = %s
                \tfetch = +refs/heads/*:refs/remotes/origin/*
                """.formatted(remoteUrl));
    }

    private void createSlot(int number, String slotContent) throws IOException {
        var slotDir = root.resolve("slots/" + number);
        Files.createDirectories(slotDir);
        Files.writeString(slotDir.resolve(".slot"), slotContent);
    }

    private void createArchivedSlot(int number, String slotContent) throws IOException {
        var slotDir = root.resolve("slots/attic/" + number);
        Files.createDirectories(slotDir);
        Files.writeString(slotDir.resolve(".slot"), slotContent);
    }

    private Path createSlotWorkspace(int slotNumber) throws IOException {
        var workDir = root.resolve("slots/" + slotNumber + "/work");
        Files.createDirectories(workDir.resolve("design"));
        return workDir;
    }
}
