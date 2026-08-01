package io.hortora.trellis.coordinator;

import io.hortora.trellis.issues.Recommendation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnhancedRecommendationCacheTest {

    @Test
    void putAndGet() {
        var cache = new EnhancedRecommendationCache();
        var rec = new EnhancedRecommendation(
                new Recommendation("owner/repo#1", "Title", Recommendation.Type.CRITICAL_PATH, 100, "reason"),
                "reasoning", List.of("factor"), 110, Instant.now());
        cache.put("owner/repo#2", List.of(rec));

        var result = cache.get("owner/repo#2");
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("reasoning", result.get(0).reasoning());
    }

    @Test
    void getReturnsNullWhenMissing() {
        var cache = new EnhancedRecommendationCache();
        assertNull(cache.get("owner/repo#99"));
    }

    @Test
    void invalidateRemovesEntry() {
        var cache = new EnhancedRecommendationCache();
        cache.put("owner/repo#2", List.of());
        cache.invalidate("owner/repo#2");
        assertNull(cache.get("owner/repo#2"));
    }

    @Test
    void putReturnsDefensiveCopy() {
        var cache = new EnhancedRecommendationCache();
        var rec = new EnhancedRecommendation(
                new Recommendation("k", "T", Recommendation.Type.BOTTLENECK, 50, "r"),
                "reasoning", List.of("f"), 50, Instant.now());
        cache.put("ref", List.of(rec));
        assertThrows(UnsupportedOperationException.class, () -> cache.get("ref").add(rec));
    }
}
