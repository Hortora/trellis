package io.hortora.trellis.issues;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@ApplicationScoped
public class IssueCache {

    private static final Logger LOG = Logger.getLogger(IssueCache.class);

    @Inject
    ObjectMapper mapper;

    private Path cacheDir = Path.of(System.getProperty("user.home"), ".trellis", "cache");

    void setCacheDir(Path dir) {
        this.cacheDir = dir;
    }

    public List<IssueInfo> load(String owner, String repo) {
        var file = cacheFile(owner, repo);
        if (!Files.isRegularFile(file)) return List.of();
        try {
            return mapper.readValue(file.toFile(), new TypeReference<>() {});
        } catch (IOException e) {
            LOG.warnf(e, "Failed to read issue cache for %s/%s", owner, repo);
            return List.of();
        }
    }

    public void save(String owner, String repo, List<IssueInfo> issues) {
        var file = cacheFile(owner, repo);
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), issues);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to write issue cache for %s/%s", owner, repo);
        }
    }

    public Instant lastUpdated(String owner, String repo) {
        var file = cacheFile(owner, repo);
        try {
            if (Files.isRegularFile(file)) {
                return Files.getLastModifiedTime(file).toInstant();
            }
        } catch (IOException ignored) {}
        return null;
    }

    private Path cacheFile(String owner, String repo) {
        return cacheDir.resolve(owner).resolve(repo).resolve("issues.json");
    }
}
