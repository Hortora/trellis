package io.hortora.trellis.protocol;

import io.hortora.trellis.scanner.RepoInfo;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@ApplicationScoped
public class ProtocolScanner {

    private static final String PROTOCOL_DIR = "docs/protocols";
    private static final String INDEX_FILE = "INDEX.md";
    private static final Pattern LINK_IN_ROW = Pattern.compile(
            "\\[([^\\]]+)]\\(([^)]+)\\)");

    public List<ProtocolIndex> findProtocolRepos(List<RepoInfo> repos) {
        List<ProtocolIndex> result = new ArrayList<>();
        for (RepoInfo repo : repos) {
            Path protocolsDir = repo.path().resolve(PROTOCOL_DIR);
            Path indexFile = protocolsDir.resolve(INDEX_FILE);
            if (Files.isRegularFile(indexFile)) {
                result.add(new ProtocolIndex(
                        repo.name(), repo.path(), indexFile,
                        PROTOCOL_DIR + "/" + INDEX_FILE));
            }
        }
        return result;
    }

    public List<Path> findIndexes(Path protocolsDir) {
        List<Path> indexes = new ArrayList<>();
        if (!Files.isDirectory(protocolsDir)) return indexes;
        try (Stream<Path> walk = Files.walk(protocolsDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toUpperCase().contains("INDEX"))
                    .filter(p -> p.toString().endsWith(".md"))
                    .forEach(indexes::add);
        } catch (IOException e) {
            // directory unreadable
        }
        return indexes;
    }

    public List<ProtocolEntry> parseIndex(Path indexPath) {
        return parseIndex(indexPath, new HashSet<>());
    }

    private List<ProtocolEntry> parseIndex(Path indexPath, Set<Path> visited) {
        Path resolved;
        try {
            resolved = indexPath.toAbsolutePath().normalize();
        } catch (Exception e) {
            return List.of();
        }
        if (!visited.add(resolved) || !Files.isRegularFile(resolved)) {
            return List.of();
        }

        List<ProtocolEntry> entries = new ArrayList<>();
        String currentSection = "";

        try {
            List<String> lines = Files.readAllLines(resolved);
            for (String line : lines) {
                if (line.startsWith("## ")) {
                    currentSection = line.substring(3).trim();
                }
                if (line.startsWith("|")) {
                    Matcher m = LINK_IN_ROW.matcher(line);
                    if (m.find()) {
                        String linkPath = m.group(2);
                        Path linkedFile = resolved.getParent().resolve(linkPath).normalize();

                        if (isSubIndex(linkPath)) {
                            entries.addAll(parseIndex(linkedFile, visited));
                        } else {
                            String[] cols = extractColumnsAfterLink(line, m.end());
                            entries.add(new ProtocolEntry(
                                    linkPath,
                                    cols.length > 0 ? cols[0] : "",
                                    cols.length > 1 ? cols[1] : "",
                                    linkedFile, currentSection));
                        }
                    }
                }
            }
        } catch (IOException e) {
            // unreadable file
        }

        return entries;
    }

    private String[] extractColumnsAfterLink(String line, int linkEnd) {
        String remainder = line.substring(linkEnd);
        String[] parts = remainder.split("\\|");
        List<String> cols = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                cols.add(trimmed);
            }
        }
        return cols.toArray(String[]::new);
    }

    private boolean isSubIndex(String path) {
        String upper = path.toUpperCase();
        return upper.contains("INDEX") && upper.endsWith(".MD");
    }
}
