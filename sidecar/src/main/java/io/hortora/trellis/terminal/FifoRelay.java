package io.hortora.trellis.terminal;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Reads bytes from a FIFO and relays decoded UTF-8 text to a consumer,
 * handling multi-byte character boundaries correctly.
 */
class FifoRelay {

    private final InputStream input;
    private final Consumer<String> sink;
    private boolean skipInitialNewline = true;

    FifoRelay(InputStream input, Consumer<String> sink) {
        this.input = input;
        this.sink = sink;
    }

    void relay() throws IOException {
        try (var reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8), 4096)) {
            var cbuf = new char[4096];
            int n;
            while ((n = reader.read(cbuf)) != -1) {
                int start = 0;
                if (skipInitialNewline) {
                    skipInitialNewline = false;
                    if (n > 0 && cbuf[0] == '\r') start++;
                    if (start < n && cbuf[start] == '\n') start++;
                    if (start >= n) continue;
                }
                sink.accept(new String(cbuf, start, n - start));
            }
        }
    }
}
