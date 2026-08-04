package io.hortora.trellis.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolServiceTest {

    @TempDir
    Path tempDir;

    private ProtocolService service;

    @BeforeEach
    void setUp() {
        service = new ProtocolService();
        service.scanner = new ProtocolScanner();
        service.gitOps = null; // git ops not tested here — unit tests for file manipulation only
    }

    @Test
    void addEntry_appendsRowToSection() throws IOException {
        Path index = tempDir.resolve("INDEX.md");
        Files.writeString(index, """
                # Protocols

                ## Build

                | File | Rule | Applies to |
                |------|------|------------|
                | [existing.md](existing.md) | Existing rule | All |
                """);

        service.addEntryNoGit(new AddEntryRequest(
                index.toString(), "Build", "new-rule.md",
                "New rule", "All projects", null, null));

        String content = Files.readString(index);
        assertTrue(content.contains("[new-rule.md](new-rule.md)"));
        assertTrue(content.contains("New rule"));
        assertTrue(content.contains("All projects"));
    }

    @Test
    void addEntry_createsProtocolFileWhenContentProvided() throws IOException {
        Path index = tempDir.resolve("INDEX.md");
        Files.writeString(index, """
                # Protocols

                ## Rules

                | File | Rule | Applies to |
                |------|------|------------|
                """);

        service.addEntryNoGit(new AddEntryRequest(
                index.toString(), "Rules", "new-protocol.md",
                "A new protocol", "All", null,
                "---\nid: PP-20260804-test01\ntitle: A new protocol\n---\n\nBody text."));

        Path protocolFile = index.getParent().resolve("new-protocol.md");
        assertTrue(Files.exists(protocolFile));
        assertTrue(Files.readString(protocolFile).contains("Body text."));
    }

    @Test
    void removeEntry_removesRowFromIndex() throws IOException {
        Path index = tempDir.resolve("INDEX.md");
        Files.writeString(index, """
                # Protocols

                ## Build

                | File | Rule | Applies to |
                |------|------|------------|
                | [keep-this.md](keep-this.md) | Keep | All |
                | [remove-this.md](remove-this.md) | Remove me | All |
                """);

        service.removeEntryNoGit(index, "remove-this.md");

        String content = Files.readString(index);
        assertFalse(content.contains("remove-this.md"));
        assertTrue(content.contains("keep-this.md"));
    }

    @Test
    void removeEntry_preservesOtherContent() throws IOException {
        Path index = tempDir.resolve("INDEX.md");
        Files.writeString(index, """
                # Protocols

                ## Build

                | File | Rule | Applies to |
                |------|------|------------|
                | [only-one.md](only-one.md) | The only rule | All |

                ## Notes

                Some notes here.
                """);

        service.removeEntryNoGit(index, "only-one.md");

        String content = Files.readString(index);
        assertFalse(content.contains("only-one.md"));
        assertTrue(content.contains("## Build"));
        assertTrue(content.contains("## Notes"));
        assertTrue(content.contains("Some notes here."));
    }

    @Test
    void insertRowInSection_appendsAfterLastTableRow() {
        String content = """
                ## Build

                | File | Rule | Applies to |
                |------|------|------------|
                | [a.md](a.md) | Rule A | All |

                ## Other
                """;

        String result = service.insertRowInSection(content, "Build",
                "| [b.md](b.md) | Rule B | All |");

        int aPos = result.indexOf("[a.md]");
        int bPos = result.indexOf("[b.md]");
        int otherPos = result.indexOf("## Other");
        assertTrue(bPos > aPos);
        assertTrue(bPos < otherPos);
    }

    @Test
    void removeRowByFile_removesCorrectRow() {
        String content = """
                | [keep.md](keep.md) | Keep | All |
                | [remove.md](remove.md) | Remove | All |
                | [also-keep.md](also-keep.md) | Also keep | All |
                """;

        String result = service.removeRowByFile(content, "remove.md");

        assertFalse(result.contains("remove.md"));
        assertTrue(result.contains("keep.md"));
        assertTrue(result.contains("also-keep.md"));
    }
}
