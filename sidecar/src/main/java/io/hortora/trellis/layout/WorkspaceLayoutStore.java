package io.hortora.trellis.layout;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@ApplicationScoped
public class WorkspaceLayoutStore {

    private static final String DIR = ".trellis";

    public String loadLayout(Path workspaceRoot) throws IOException {
        var file = workspaceRoot.resolve(DIR).resolve("layout.json");
        if (!Files.exists(file)) return null;
        return Files.readString(file);
    }

    public void saveLayout(Path workspaceRoot, String json) throws IOException {
        var dir = workspaceRoot.resolve(DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("layout.json"), json);
    }

    public String loadGroups(Path workspaceRoot) throws IOException {
        var file = workspaceRoot.resolve(DIR).resolve("groups.json");
        if (!Files.exists(file)) return null;
        return Files.readString(file);
    }

    public void saveGroups(Path workspaceRoot, String json) throws IOException {
        var dir = workspaceRoot.resolve(DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("groups.json"), json);
    }
}
