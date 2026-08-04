package io.hortora.trellis.protocol;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@ApplicationScoped
public class ProtocolService {

    @Inject
    ProtocolScanner scanner;

    @Inject
    GitOps gitOps;

    private final ConcurrentHashMap<Path, ReentrantLock> locks = new ConcurrentHashMap<>();

    private final AtomicInteger suppressWatcherCount = new AtomicInteger(0);

    public boolean isSuppressingWatcher() {
        return suppressWatcherCount.get() > 0;
    }

    public void addEntry(AddEntryRequest request) throws IOException {
        Path indexPath = Path.of(request.indexPath()).toAbsolutePath().normalize();
        ReentrantLock lock = locks.computeIfAbsent(indexPath, k -> new ReentrantLock());
        lock.lock();
        try {
            List<Path> filesToCommit = new ArrayList<>();
            filesToCommit.add(indexPath);

            String original = addEntryNoGit(request);

            if (request.content() != null && !request.content().isBlank()) {
                filesToCommit.add(indexPath.getParent().resolve(request.file()));
            }

            try {
                Path repoRoot = findRepoRoot(indexPath);
                suppressWatcherCount.incrementAndGet();
                gitOps.commitFiles(repoRoot, filesToCommit,
                        "protocol: add " + request.file() + " to " + indexPath.getFileName());
            } catch (IOException e) {
                Files.writeString(indexPath, original);
                if (request.content() != null && !request.content().isBlank()) {
                    Path orphan = indexPath.getParent().resolve(request.file()).normalize();
                    Files.deleteIfExists(orphan);
                }
                throw e;
            } finally {
                suppressWatcherCount.decrementAndGet();
            }
        } finally {
            lock.unlock();
        }
    }

    public void removeEntry(Path indexPath, String file) throws IOException {
        indexPath = indexPath.toAbsolutePath().normalize();
        ReentrantLock lock = locks.computeIfAbsent(indexPath, k -> new ReentrantLock());
        lock.lock();
        try {
            String original = removeEntryNoGit(indexPath, file);
            String updated = Files.readString(indexPath);

            if (updated.equals(original)) return;

            try {
                Path repoRoot = findRepoRoot(indexPath);
                suppressWatcherCount.incrementAndGet();
                gitOps.commitFiles(repoRoot, List.of(indexPath),
                        "protocol: remove " + file + " from " + indexPath.getFileName());
            } catch (IOException e) {
                Files.writeString(indexPath, original);
                throw e;
            } finally {
                suppressWatcherCount.decrementAndGet();
            }
        } finally {
            lock.unlock();
        }
    }

    /** File manipulation only — returns original content for rollback. */
    String addEntryNoGit(AddEntryRequest request) throws IOException {
        Path indexPath = Path.of(request.indexPath()).toAbsolutePath().normalize();
        String original = Files.readString(indexPath);

        if (request.content() != null && !request.content().isBlank()) {
            Path protocolFile = indexPath.getParent().resolve(request.file()).normalize();
            if (!protocolFile.startsWith(indexPath.getParent())) {
                throw new IOException("File path escapes protocols directory: " + request.file());
            }
            Files.writeString(protocolFile, request.content());
        }

        String newRow = "| [" + request.file() + "](" + request.file() + ") | "
                + request.summary() + " | " + request.appliesTo() + " |";

        String updated = insertRowInSection(original, request.section(), newRow);
        Files.writeString(indexPath, updated);

        return original;
    }

    /** File manipulation only — returns original content for rollback. */
    String removeEntryNoGit(Path indexPath, String file) throws IOException {
        indexPath = indexPath.toAbsolutePath().normalize();
        String original = Files.readString(indexPath);
        String updated = removeRowByFile(original, file);
        Files.writeString(indexPath, updated);
        return original;
    }

    String insertRowInSection(String content, String sectionName, String newRow) {
        String[] lines = content.split("\n", -1);
        List<String> result = new ArrayList<>();
        boolean inTargetSection = false;
        int lastTableRow = -1;

        for (int i = 0; i < lines.length; i++) {
            result.add(lines[i]);
            if (lines[i].startsWith("## ")) {
                String heading = lines[i].substring(3).trim();
                if (heading.equals(sectionName)) {
                    inTargetSection = true;
                } else if (inTargetSection) {
                    result.add(result.size() - 1, newRow);
                    return String.join("\n", result);
                }
            }
            if (inTargetSection && lines[i].startsWith("|") && !lines[i].contains("---")) {
                lastTableRow = result.size() - 1;
            }
        }

        if (lastTableRow >= 0) {
            result.add(lastTableRow + 1, newRow);
        }

        return String.join("\n", result);
    }

    String removeRowByFile(String content, String file) {
        String[] lines = content.split("\n", -1);
        List<String> result = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("|") && (line.contains("(" + file + ")") || line.contains("[" + file + "]"))) {
                continue;
            }
            result.add(line);
        }
        return String.join("\n", result);
    }

    private Path findRepoRoot(Path path) {
        Path current = path.getParent();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("No git repo found for " + path);
    }
}
