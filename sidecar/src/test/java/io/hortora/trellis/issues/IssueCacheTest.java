package io.hortora.trellis.issues;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IssueCacheTest {

    @TempDir
    Path tempDir;

    IssueCache cache;

    @BeforeEach
    void setUp() {
        cache = new IssueCache();
        var mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        cache.mapper = mapper;
        cache.setCacheDir(tempDir);
    }

    @Test
    void saveAndLoadRoundTrips() {
        var issues = List.of(
                new IssueInfo("org", "repo", 1, "First", "OPEN", List.of(), "body", null),
                new IssueInfo("org", "repo", 2, "Second", "CLOSED", List.of("bug"), null, null)
        );

        cache.save("org", "repo", issues);
        var loaded = cache.load("org", "repo");

        assertEquals(2, loaded.size());
        assertEquals("First", loaded.get(0).title());
        assertEquals("CLOSED", loaded.get(1).state());
        assertEquals(List.of("bug"), loaded.get(1).labels());
    }

    @Test
    void loadReturnsEmptyForMissing() {
        var loaded = cache.load("nonexistent", "repo");

        assertTrue(loaded.isEmpty());
    }

    @Test
    void lastUpdatedReturnsNullForMissing() {
        assertNull(cache.lastUpdated("nonexistent", "repo"));
    }

    @Test
    void lastUpdatedReturnsTimestamp() {
        cache.save("org", "repo", List.of());

        assertNotNull(cache.lastUpdated("org", "repo"));
    }
}
