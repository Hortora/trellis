package io.hortora.trellis.scanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
    void scanSkipsAtticDirectory() throws IOException {
        createRepo("engine");
        Files.createDirectories(root.resolve("worktrees/attic/1/engine/.git"));

        var model = scanner.scan(root);

        assertEquals(1, model.repos().size());
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
    void scanDetectsReadyToLandSlot() throws IOException {
        createSlot(45, """
                # Slot 45 — issue-100-feature
                
                ## Issue
                casehubio/blocks-ui#100
                Covers: 100
                
                ## Repos
                - blocks-ui (primary)
                - chat-app
                """);
        Files.createFile(root.resolve("worktrees/45/.phase-a-complete"));

        var model = scanner.scan(root);

        assertEquals(SlotStatus.READY_TO_LAND, model.slots().getFirst().status());
        assertFalse(model.slots().getFirst().isEpic());
        assertEquals(List.of("blocks-ui", "chat-app"), model.slots().getFirst().repos());
    }

    @Test
    void scanSkipsSlotDirectoriesWithoutSlotFile() throws IOException {
        Files.createDirectories(root.resolve("worktrees/99"));

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
        var slotDir = root.resolve("worktrees/" + number);
        Files.createDirectories(slotDir);
        Files.writeString(slotDir.resolve(".slot"), slotContent);
    }

    private Path createSlotWorkspace(int slotNumber) throws IOException {
        var workDir = root.resolve("worktrees/" + slotNumber + "/work");
        Files.createDirectories(workDir.resolve("design"));
        return workDir;
    }
}
