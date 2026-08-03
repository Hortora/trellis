package io.hortora.trellis.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactScannerTest {

    @TempDir Path tempDir;

    private final ArtifactScanner scanner = new ArtifactScanner();

    private Path setupWorkspace() throws IOException {
        var ws = tempDir.resolve("workspace");
        Files.createDirectories(ws);
        return ws;
    }

    private Path setupProject(Path ws) throws IOException {
        var proj = tempDir.resolve("project");
        Files.createDirectories(proj);
        Files.createSymbolicLink(ws.resolve("proj"), proj);
        return proj;
    }

    @Test
    void findsSpecsInWorkspaceAndProject() throws IOException {
        var ws = setupWorkspace();
        var proj = setupProject(ws);
        Files.createDirectories(ws.resolve("specs"));
        Files.writeString(ws.resolve("specs/design-a.md"), "# Spec A");
        Files.createDirectories(proj.resolve("docs/specs"));
        Files.writeString(proj.resolve("docs/specs/design-b.md"), "# Spec B");

        var entries = scanner.scan(ws);
        var specs = entries.stream().filter(e -> e.type().equals("spec")).toList();
        assertEquals(2, specs.size());
    }

    @Test
    void findsSingleFileArtifacts() throws IOException {
        var ws = setupWorkspace();
        var proj = setupProject(ws);
        Files.writeString(ws.resolve("HANDOFF.md"), "# Handoff");
        Files.createDirectories(proj.resolve("docs"));
        Files.writeString(proj.resolve("docs/ARC42STORIES.MD"), "# Design");

        var entries = scanner.scan(ws);
        assertTrue(entries.stream().anyMatch(e -> e.type().equals("handover")));
        assertTrue(entries.stream().anyMatch(e -> e.type().equals("design")));
    }

    @Test
    void handlesMissingDirectoriesGracefully() throws IOException {
        var ws = setupWorkspace();
        setupProject(ws);
        var entries = scanner.scan(ws);
        assertTrue(entries.isEmpty());
    }

    @Test
    void handlesBrokenProjSymlink() throws IOException {
        var ws = setupWorkspace();
        Files.createSymbolicLink(ws.resolve("proj"), tempDir.resolve("nonexistent"));
        Files.createDirectories(ws.resolve("specs"));
        Files.writeString(ws.resolve("specs/design-a.md"), "# Spec A");

        var entries = scanner.scan(ws);
        assertEquals(1, entries.size());
        assertEquals("spec", entries.get(0).type());
    }

    @Test
    void skipsIndexFiles() throws IOException {
        var ws = setupWorkspace();
        setupProject(ws);
        Files.createDirectories(ws.resolve("specs"));
        Files.writeString(ws.resolve("specs/INDEX.md"), "# Index");
        Files.writeString(ws.resolve("specs/real-spec.md"), "# Real");

        var entries = scanner.scan(ws);
        assertEquals(1, entries.size());
        assertEquals("real-spec", entries.get(0).name());
    }

    @Test
    void sortsByTypeOrderThenAlphabetically() throws IOException {
        var ws = setupWorkspace();
        setupProject(ws);
        Files.createDirectories(ws.resolve("specs"));
        Files.createDirectories(ws.resolve("adr"));
        Files.writeString(ws.resolve("specs/z-spec.md"), "# Z");
        Files.writeString(ws.resolve("specs/a-spec.md"), "# A");
        Files.writeString(ws.resolve("adr/adr-001.md"), "# ADR");

        var entries = scanner.scan(ws);
        assertEquals("spec", entries.get(0).type());
        assertEquals("a-spec", entries.get(0).name());
        assertEquals("z-spec", entries.get(1).name());
        assertEquals("adr", entries.get(2).type());
    }

    @Test
    void handlesNoProjSymlink() throws IOException {
        var ws = setupWorkspace();
        Files.createDirectories(ws.resolve("blog"));
        Files.writeString(ws.resolve("blog/entry.md"), "# Blog");

        var entries = scanner.scan(ws);
        assertEquals(1, entries.size());
        assertEquals("blog", entries.get(0).type());
    }

    @Test
    void findsJournalInDesignDirectory() throws IOException {
        var ws = setupWorkspace();
        Files.createDirectories(ws.resolve("design"));
        Files.writeString(ws.resolve("design/JOURNAL.md"), "# Journal");

        var entries = scanner.scan(ws);
        assertEquals(1, entries.size());
        assertEquals("journal", entries.get(0).type());
    }

    @Test
    void walksNestedSpecDirectories() throws IOException {
        var ws = setupWorkspace();
        setupProject(ws);
        Files.createDirectories(ws.resolve("specs/epic-2-post-mvp"));
        Files.writeString(ws.resolve("specs/epic-2-post-mvp/nested-spec.md"), "# Nested");

        var entries = scanner.scan(ws);
        assertEquals(1, entries.size());
        assertEquals("nested-spec", entries.get(0).name());
    }
}
