package io.hortora.trellis.terminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SessionLoggerTest {

    @Test
    void appendAndTailRead(@TempDir Path tempDir) {
        var logger = new SessionLogger(tempDir);
        logger.append("test-term", "line1\nline2\nline3\n");

        var result = logger.tailLines("test-term", 2);
        assertEquals("line2\nline3\n", result);
    }

    @Test
    void tailReadAllLinesWhenFewerThanRequested(@TempDir Path tempDir) {
        var logger = new SessionLogger(tempDir);
        logger.append("test-term", "only\n");

        var result = logger.tailLines("test-term", 10);
        assertEquals("only\n", result);
    }

    @Test
    void tailReadEmptyLogReturnsEmpty(@TempDir Path tempDir) {
        var logger = new SessionLogger(tempDir);
        var result = logger.tailLines("nonexistent", 10);
        assertEquals("", result);
    }

    @Test
    void tailReadWithOffset(@TempDir Path tempDir) {
        var logger = new SessionLogger(tempDir);
        logger.append("test-term", "a\nb\nc\nd\ne\n");

        var result = logger.tailLinesWithOffset("test-term", 2, 1);
        assertEquals("c\nd\n", result);
    }

    @Test
    void appendIsAdditive(@TempDir Path tempDir) {
        var logger = new SessionLogger(tempDir);
        logger.append("test-term", "first\n");
        logger.append("test-term", "second\n");

        var result = logger.tailLines("test-term", 10);
        assertEquals("first\nsecond\n", result);
    }

    @Test
    void deleteRemovesLogFile(@TempDir Path tempDir) {
        var logger = new SessionLogger(tempDir);
        logger.append("test-term", "data\n");
        assertTrue(Files.exists(tempDir.resolve("test-term.log")));

        logger.delete("test-term");
        assertFalse(Files.exists(tempDir.resolve("test-term.log")));
    }

    @Test
    void deleteNonexistentIsNoOp(@TempDir Path tempDir) {
        var logger = new SessionLogger(tempDir);
        assertDoesNotThrow(() -> logger.delete("nonexistent"));
    }

    @Test
    void appendMarkerWritesBracketedSequence(@TempDir Path tempDir) {
        var logger = new SessionLogger(tempDir);
        logger.appendMarker("test-term", "echo hello");

        var result = logger.tailLines("test-term", 1);
        assertTrue(result.contains("\033[?2004h"));
        assertTrue(result.contains("echo hello"));
        assertTrue(result.contains("\033[?2004l"));
    }

    @Test
    void logPathReturnsCorrectPath(@TempDir Path tempDir) {
        var logger = new SessionLogger(tempDir);
        assertEquals(tempDir.resolve("test-term.log"), logger.logPath("test-term"));
    }

    @Test
    void tailReadPreservesAnsiSequences(@TempDir Path tempDir) {
        var logger = new SessionLogger(tempDir);
        var ansi = "\033[32mgreen\033[0m\n\033[31mred\033[0m\n";
        logger.append("test-term", ansi);

        var result = logger.tailLines("test-term", 2);
        assertEquals(ansi, result);
    }
}
