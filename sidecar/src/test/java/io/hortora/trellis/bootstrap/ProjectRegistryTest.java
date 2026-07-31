package io.hortora.trellis.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRegistryTest {

    @TempDir
    Path tempDir;

    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
    }

    @Test
    void loadsValidProjectsJson() throws IOException {
        var json = """
            [
              {
                "id": "casehub",
                "name": "CaseHub",
                "description": "Case management platform",
                "parentRepoUrl": "https://github.com/Org/casehub-parent.git",
                "setupCommand": "./setup.sh",
                "expectedStructure": ["engine", "worker", "app"]
              }
            ]
            """;
        Path file = tempDir.resolve("projects.json");
        Files.writeString(file, json);

        var registry = ProjectRegistry.fromFile(file, mapper);

        assertEquals(1, registry.list().size());
        var project = registry.list().get(0);
        assertEquals("casehub", project.id());
        assertEquals("CaseHub", project.name());
        assertEquals("https://github.com/Org/casehub-parent.git", project.parentRepoUrl());
        assertEquals("./setup.sh", project.setupCommand());
        assertEquals(List.of("engine", "worker", "app"), project.expectedStructure());
    }

    @Test
    void findByIdReturnsProject() throws IOException {
        var json = """
            [
              {"id": "alpha", "name": "Alpha", "description": "A", "parentRepoUrl": "https://a.git", "setupCommand": "./a.sh", "expectedStructure": []},
              {"id": "beta", "name": "Beta", "description": "B", "parentRepoUrl": "https://b.git", "setupCommand": "./b.sh", "expectedStructure": ["x"]}
            ]
            """;
        Path file = tempDir.resolve("projects.json");
        Files.writeString(file, json);

        var registry = ProjectRegistry.fromFile(file, mapper);

        assertTrue(registry.findById("alpha").isPresent());
        assertTrue(registry.findById("beta").isPresent());
        assertEquals("Beta", registry.findById("beta").get().name());
        assertTrue(registry.findById("unknown").isEmpty());
    }

    @Test
    void rejectsProjectWithoutId() throws IOException {
        var json = """
            [{"name": "NoId", "description": "X", "parentRepoUrl": "https://x.git", "setupCommand": "./x.sh", "expectedStructure": []}]
            """;
        Path file = tempDir.resolve("projects.json");
        Files.writeString(file, json);

        assertThrows(IllegalArgumentException.class, () -> ProjectRegistry.fromFile(file, mapper));
    }

    @Test
    void rejectsProjectWithoutRepoUrl() throws IOException {
        var json = """
            [{"id": "bad", "name": "Bad", "description": "X", "setupCommand": "./x.sh", "expectedStructure": []}]
            """;
        Path file = tempDir.resolve("projects.json");
        Files.writeString(file, json);

        assertThrows(IllegalArgumentException.class, () -> ProjectRegistry.fromFile(file, mapper));
    }

    @Test
    void rejectsDuplicateIds() throws IOException {
        var json = """
            [
              {"id": "dup", "name": "First", "description": "A", "parentRepoUrl": "https://a.git", "setupCommand": "./a.sh", "expectedStructure": []},
              {"id": "dup", "name": "Second", "description": "B", "parentRepoUrl": "https://b.git", "setupCommand": "./b.sh", "expectedStructure": []}
            ]
            """;
        Path file = tempDir.resolve("projects.json");
        Files.writeString(file, json);

        assertThrows(IllegalArgumentException.class, () -> ProjectRegistry.fromFile(file, mapper));
    }

    @Test
    void emptyRegistryReturnsEmptyList() throws IOException {
        Path file = tempDir.resolve("projects.json");
        Files.writeString(file, "[]");

        var registry = ProjectRegistry.fromFile(file, mapper);
        assertTrue(registry.list().isEmpty());
    }
}
