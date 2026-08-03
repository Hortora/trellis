package io.hortora.trellis.artifact;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ArtifactScanner {

    private static final Logger LOG = Logger.getLogger(ArtifactScanner.class);

    private static final List<ArtifactType> TYPES = List.of(
            new ArtifactType("spec", "specs", "docs/specs"),
            new ArtifactType("adr", "adr", "docs/adr"),
            new ArtifactType("plan", "plans", "docs/plans"),
            new ArtifactType("blog", "blog", null)
    );

    private record ArtifactType(String type, String wsDir, String projDir) {}

    public List<ArtifactEntry> scan(Path workspaceRoot) {
        Path projectRoot = resolveProjectRoot(workspaceRoot);
        var entries = new ArrayList<ArtifactEntry>();

        for (var at : TYPES) {
            scanDirectory(workspaceRoot.resolve(at.wsDir()), at.type(), entries);
            if (projectRoot != null && at.projDir() != null) {
                scanDirectory(projectRoot.resolve(at.projDir()), at.type(), entries);
            }
        }

        scanSingleFile(workspaceRoot.resolve("HANDOFF.md"), "handover", entries);
        if (projectRoot != null) {
            scanSingleFile(projectRoot.resolve("docs/ARC42STORIES.MD"), "design", entries);
        }
        scanSingleFile(workspaceRoot.resolve("design/JOURNAL.md"), "journal", entries);

        entries.sort((a, b) -> {
            int typeOrder = typeIndex(a.type()) - typeIndex(b.type());
            return typeOrder != 0 ? typeOrder : a.name().compareToIgnoreCase(b.name());
        });

        return entries;
    }

    private Path resolveProjectRoot(Path workspaceRoot) {
        var projLink = workspaceRoot.resolve("proj");
        if (!Files.isSymbolicLink(projLink)) {
            LOG.debugf("No proj/ symlink in workspace %s", workspaceRoot);
            return null;
        }
        try {
            var resolved = projLink.toRealPath();
            if (Files.isDirectory(resolved)) return resolved;
            LOG.warnf("proj/ symlink target does not exist: %s", resolved);
            return null;
        } catch (IOException e) {
            LOG.warnf("Failed to resolve proj/ symlink: %s", e.getMessage());
            return null;
        }
    }

    private void scanDirectory(Path dir, String type, List<ArtifactEntry> entries) {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> p.toString().endsWith(".md"))
                  .filter(p -> !p.getFileName().toString().equals("INDEX.md"))
                  .forEach(p -> {
                      try {
                          var name = p.getFileName().toString().replaceFirst("\\.md$", "");
                          var modified = Files.getLastModifiedTime(p).toInstant();
                          entries.add(new ArtifactEntry(type, name, p.toAbsolutePath().toString(), modified));
                      } catch (IOException e) {
                          LOG.debugf("Failed to read %s: %s", p, e.getMessage());
                      }
                  });
        } catch (IOException e) {
            LOG.debugf("Failed to scan %s: %s", dir, e.getMessage());
        }
    }

    private void scanSingleFile(Path file, String type, List<ArtifactEntry> entries) {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            var name = file.getFileName().toString().replaceFirst("\\.[mM][dD]$", "");
            var modified = Files.getLastModifiedTime(file).toInstant();
            entries.add(new ArtifactEntry(type, name, file.toAbsolutePath().toString(), modified));
        } catch (IOException e) {
            LOG.debugf("Failed to read %s: %s", file, e.getMessage());
        }
    }

    private static final List<String> TYPE_ORDER = List.of(
            "spec", "adr", "plan", "blog", "handover", "design", "journal"
    );

    private int typeIndex(String type) {
        int idx = TYPE_ORDER.indexOf(type);
        return idx >= 0 ? idx : TYPE_ORDER.size();
    }
}
