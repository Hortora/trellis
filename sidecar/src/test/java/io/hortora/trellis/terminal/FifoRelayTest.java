package io.hortora.trellis.terminal;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FifoRelayTest {

    @Test
    void relaysSimpleAsciiText() throws IOException {
        var input = new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8));
        var chunks = new ArrayList<String>();

        new FifoRelay(input, chunks::add).relay();

        assertEquals("hello world", String.join("", chunks));
    }

    @Test
    void preservesMultiByteUtf8Characters() throws IOException {
        String text = "arrow → bullet • check ✓ emoji 🎉";
        var input = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        var chunks = new ArrayList<String>();

        new FifoRelay(input, chunks::add).relay();

        assertEquals(text, String.join("", chunks));
    }

    @Test
    void handlesUtf8CharacterSplitAtChunkBoundary() throws IOException {
        // Build a byte array where a 3-byte UTF-8 character (→ = E2 86 92)
        // straddles the read buffer boundary.
        // Fill buffer with ASCII up to position 4095, then put → (3 bytes)
        // so byte 4095 = E2, bytes 4096-4097 = 86 92
        byte[] arrow = "→".getBytes(StandardCharsets.UTF_8);
        assertEquals(3, arrow.length, "→ should be 3 bytes in UTF-8");

        byte[] data = new byte[4095 + arrow.length + 5]; // padding after
        java.util.Arrays.fill(data, 0, 4095, (byte) 'A');
        System.arraycopy(arrow, 0, data, 4095, arrow.length);
        java.util.Arrays.fill(data, 4095 + arrow.length, data.length, (byte) 'B');

        // Demonstrate the bug with raw byte reading
        var rawChunks = readWithRawBytes(data, 4096);
        String rawResult = String.join("", rawChunks);
        // Raw approach corrupts the arrow character
        assertNotEquals(new String(data, StandardCharsets.UTF_8), rawResult,
                "Raw byte reading should corrupt the split character");

        // FifoRelay handles it correctly
        var relayChunks = new ArrayList<String>();
        new FifoRelay(new ByteArrayInputStream(data), relayChunks::add).relay();
        String relayResult = String.join("", relayChunks);
        assertEquals(new String(data, StandardCharsets.UTF_8), relayResult,
                "FifoRelay should preserve the split character");
    }

    @Test
    void handles4ByteEmojiSplitAtChunkBoundary() throws IOException {
        // 🎉 = F0 9F 8E 89 (4 bytes)
        byte[] emoji = "🎉".getBytes(StandardCharsets.UTF_8);
        assertEquals(4, emoji.length);

        byte[] data = new byte[4094 + emoji.length + 4];
        java.util.Arrays.fill(data, 0, 4094, (byte) 'X');
        System.arraycopy(emoji, 0, data, 4094, emoji.length);
        java.util.Arrays.fill(data, 4094 + emoji.length, data.length, (byte) 'Y');

        var chunks = new ArrayList<String>();
        new FifoRelay(new ByteArrayInputStream(data), chunks::add).relay();
        String result = String.join("", chunks);

        assertTrue(result.contains("🎉"), "Should preserve 4-byte emoji");
        assertEquals(new String(data, StandardCharsets.UTF_8), result);
    }

    @Test
    void skipsInitialNewline() throws IOException {
        var input = new ByteArrayInputStream("\nhello".getBytes(StandardCharsets.UTF_8));
        var chunks = new ArrayList<String>();

        new FifoRelay(input, chunks::add).relay();

        assertEquals("hello", String.join("", chunks));
    }

    @Test
    void skipsInitialCrLf() throws IOException {
        var input = new ByteArrayInputStream("\r\nhello".getBytes(StandardCharsets.UTF_8));
        var chunks = new ArrayList<String>();

        new FifoRelay(input, chunks::add).relay();

        assertEquals("hello", String.join("", chunks));
    }

    @Test
    void doesNotSkipNewlineAfterContent() throws IOException {
        var input = new ByteArrayInputStream("a\nhello".getBytes(StandardCharsets.UTF_8));
        var chunks = new ArrayList<String>();

        new FifoRelay(input, chunks::add).relay();

        assertEquals("a\nhello", String.join("", chunks));
    }

    @Test
    void preservesAnsiEscapeSequences() throws IOException {
        String ansi = "\033[31mred\033[0m \033[1;32mbold green\033[0m";
        var input = new ByteArrayInputStream(ansi.getBytes(StandardCharsets.UTF_8));
        var chunks = new ArrayList<String>();

        new FifoRelay(input, chunks::add).relay();

        assertEquals(ansi, String.join("", chunks));
    }

    @Test
    void handlesEmptyInput() throws IOException {
        var input = new ByteArrayInputStream(new byte[0]);
        var chunks = new ArrayList<String>();

        new FifoRelay(input, chunks::add).relay();

        assertTrue(chunks.isEmpty());
    }

    private static List<String> readWithRawBytes(byte[] data, int bufSize) {
        var chunks = new ArrayList<String>();
        var in = new ByteArrayInputStream(data);
        var buf = new byte[bufSize];
        int n;
        while ((n = in.read(buf, 0, buf.length)) != -1) {
            chunks.add(new String(buf, 0, n));
        }
        return chunks;
    }
}
