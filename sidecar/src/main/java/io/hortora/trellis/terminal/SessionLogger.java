package io.hortora.trellis.terminal;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@ApplicationScoped
public class SessionLogger {

    private static final Logger LOG = Logger.getLogger(SessionLogger.class);

    private final Path sessionsDir;

    SessionLogger(@ConfigProperty(name = "trellis.session-log.dir",
            defaultValue = "${java.io.tmpdir}/trellis-sessions") Path sessionsDir) {
        this.sessionsDir = sessionsDir;
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void append(String terminalName, String text) {
        try {
            Files.writeString(logPath(terminalName), text,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warnf("Session log write failed for %s: %s", terminalName, e.getMessage());
        }
    }

    public String tailLines(String terminalName, int lines) {
        return tailLinesWithOffset(terminalName, lines, 0);
    }

    public String tailLinesWithOffset(String terminalName, int lines, int offset) {
        var path = logPath(terminalName);
        if (!Files.exists(path)) {return "";}

        try (var raf = new RandomAccessFile(path.toFile(), "r")) {
            long fileLength = raf.length();
            if (fileLength == 0) {return "";}

            int  totalLines    = lines + offset;
            int  newlinesFound = 0;
            long pos           = fileLength - 1;

            // Skip trailing newline — it terminates the last line, not a separator
            raf.seek(pos);
            if (raf.readByte() == '\n') {pos--;}

            // Scan backwards for totalLines newline separators
            while (pos > 0 && newlinesFound < totalLines) {
                raf.seek(pos);
                if (raf.readByte() == '\n') {newlinesFound++;}
                pos--;
            }

            long startPos;
            if (pos == 0 && newlinesFound < totalLines) {
                // Check if byte at position 0 is also a newline
                raf.seek(0);
                if (raf.readByte() == '\n') {newlinesFound++;}
                startPos = (newlinesFound >= totalLines) ? 1 : 0;
            } else {
                startPos = pos + 2;
            }

            long endPos = fileLength;
            if (offset > 0) {
                // Find the offset-th newline from the end to truncate
                int  skipLines = 0;
                long ep        = fileLength - 1;
                // Skip trailing newline
                raf.seek(ep);
                if (raf.readByte() == '\n') {ep--;}

                while (ep > startPos && skipLines < offset) {
                    raf.seek(ep);
                    if (raf.readByte() == '\n') {skipLines++;}
                    ep--;
                }
                endPos = ep + 2;
            }

            int len = (int) (endPos - startPos);
            if (len <= 0) {return "";}
            raf.seek(startPos);
            byte[] buf = new byte[len];
            raf.readFully(buf);
            return new String(buf);
        } catch (IOException e) {
            LOG.warnf("Session log read failed for %s: %s", terminalName, e.getMessage());
            return "";
        }
    }

    public Path logPath(String terminalName) {
        return sessionsDir.resolve(terminalName + ".log");
    }

    public void delete(String terminalName) {
        try {
            Files.deleteIfExists(logPath(terminalName));
        } catch (IOException e) {
            LOG.warnf("Session log delete failed for %s: %s", terminalName, e.getMessage());
        }
    }

    public void appendMarker(String terminalName, String text) {
        append(terminalName, "\033[?2004h" + text + "\033[?2004l\n");
    }
}
