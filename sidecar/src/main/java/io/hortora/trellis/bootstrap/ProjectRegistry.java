package io.hortora.trellis.bootstrap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

public class ProjectRegistry {

    private final List<ProjectEntry> projects;

    private ProjectRegistry(List<ProjectEntry> projects) {
        this.projects = List.copyOf(projects);
    }

    public static ProjectRegistry fromFile(Path file, ObjectMapper mapper) throws IOException {
        List<ProjectEntry> entries = mapper.readValue(file.toFile(), new TypeReference<>() {});
        validate(entries);
        return new ProjectRegistry(entries);
    }

    public static ProjectRegistry fromEntries(List<ProjectEntry> entries) {
        validate(entries);
        return new ProjectRegistry(entries);
    }


    public List<ProjectEntry> list() {
        return projects;
    }

    public Optional<ProjectEntry> findById(String id) {
        return projects.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    private static void validate(List<ProjectEntry> entries) {
        var ids = new HashSet<String>();
        for (var entry : entries) {
            if (entry.id() == null || entry.id().isBlank()) {
                throw new IllegalArgumentException("Project entry missing id: " + entry.name());
            }
            if (entry.parentRepoUrl() == null || entry.parentRepoUrl().isBlank()) {
                throw new IllegalArgumentException("Project entry missing parentRepoUrl: " + entry.id());
            }
            if (!ids.add(entry.id())) {
                throw new IllegalArgumentException("Duplicate project id: " + entry.id());
            }
        }
    }
}
