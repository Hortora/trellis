package io.hortora.trellis.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolScannerTest {

    private ProtocolScanner scanner;

    @BeforeEach
    void setUp() {
        scanner = new ProtocolScanner();
    }

    @Test
    void parseDirectIndex_extractsEntries() {
        Path index = Path.of("src/test/resources/protocols/direct-index/INDEX.md");
        List<ProtocolEntry> entries = scanner.parseIndex(index);

        assertEquals(2, entries.size());

        var first = entries.get(0);
        assertEquals("maven-coordinate-standard.md", first.file());
        assertEquals("Maven coordinate standard", first.summary());
        assertEquals("Any Maven project", first.appliesTo());
        assertEquals("Maven / Build", first.section());
    }

    @Test
    void parseDirectIndex_handlesVaryingColumnHeaders() {
        Path index = Path.of("src/test/resources/protocols/direct-index/INDEX.md");
        List<ProtocolEntry> entries = scanner.parseIndex(index);

        var second = entries.get(1);
        assertEquals("java-optional-usage.md", second.file());
        assertEquals("Java / Architecture", second.section());
    }

    @Test
    void parseRouterIndex_followsSubIndexes() {
        Path index = Path.of("src/test/resources/protocols/router-index/INDEX.md");
        List<ProtocolEntry> entries = scanner.parseIndex(index);

        assertEquals(1, entries.size());
        assertEquals("some-rule.md", entries.get(0).file());
        assertEquals("A universal rule", entries.get(0).summary());
    }

    @Test
    void parseIndex_handlesNonExistentFile() {
        Path index = Path.of("src/test/resources/protocols/nonexistent/INDEX.md");
        List<ProtocolEntry> entries = scanner.parseIndex(index);
        assertTrue(entries.isEmpty());
    }

    @Test
    void findIndexes_discoversAllIndexFiles() {
        Path protocolsDir = Path.of("src/test/resources/protocols/router-index");
        List<Path> indexes = scanner.findIndexes(protocolsDir);

        assertTrue(indexes.size() >= 2);
        assertTrue(indexes.stream().anyMatch(p -> p.endsWith("INDEX.md")));
        assertTrue(indexes.stream().anyMatch(p -> p.toString().contains("universal")));
    }
}
