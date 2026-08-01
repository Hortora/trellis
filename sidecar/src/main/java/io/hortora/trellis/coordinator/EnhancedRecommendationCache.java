package io.hortora.trellis.coordinator;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class EnhancedRecommendationCache {

    private final ConcurrentHashMap<String, List<EnhancedRecommendation>> cache = new ConcurrentHashMap<>();

    public void put(String epicRef, List<EnhancedRecommendation> recommendations) {
        cache.put(epicRef, List.copyOf(recommendations));
    }

    public List<EnhancedRecommendation> get(String epicRef) {
        return cache.get(epicRef);
    }

    public void invalidate(String epicRef) {
        cache.remove(epicRef);
    }
}
