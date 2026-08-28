package io.hortora.trellis.layout;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class LayoutStore {

    private static final String DIR = ".trellis/layouts";

    public String load(Path workspaceRoot, String key) throws IOException {
        var file = workspaceRoot.resolve(DIR).resolve(key + ".json");
        if (!Files.exists(file)) return null;
        return Files.readString(file);
    }

    public void save(Path workspaceRoot, String key, String json) throws IOException {
        var dir = workspaceRoot.resolve(DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(key + ".json"), json);
    }

    public void delete(Path workspaceRoot, String key) throws IOException {
        var file = workspaceRoot.resolve(DIR).resolve(key + ".json");
        Files.deleteIfExists(file);
    }
}
