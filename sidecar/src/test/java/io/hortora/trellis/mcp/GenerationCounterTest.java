package io.hortora.trellis.mcp;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class GenerationCounterTest {

    @Inject
    GenerationCounter counter;

    @Inject
    TrellisTools tools;

    @Inject
    UIStateStore store;

    @Test
    void modelResponseIncludesGeneration() {
        var result = tools.trellisModel(null);
        var text = result.firstContent().toString();
        assertTrue(text.contains("\"generation\""));
    }

    @Test
    void generationIncrementsOnUIStatePush() {
        long before = counter.current();
        store.update(Map.of("activePanel", "test"));
        long after = counter.current();
        assertTrue(after > before);
    }
}
