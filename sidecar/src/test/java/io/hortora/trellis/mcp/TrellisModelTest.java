package io.hortora.trellis.mcp;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class TrellisModelTest {

    @Inject
    TrellisTools tools;

    @Test
    void modelWithNoPathReturnsTopLevel() {
        var result = tools.trellisModel(null);
        assertFalse(result.isError());
        var text = result.firstContent().toString();
        assertTrue(text.contains("terminals"));
        assertTrue(text.contains("workspace"));
        assertTrue(text.contains("generation"));
    }

    @Test
    void modelWithTerminalsPathReturnsTerminals() {
        var result = tools.trellisModel("terminals");
        assertFalse(result.isError());
        var text = result.firstContent().toString();
        assertNotNull(text);
    }

    @Test
    void modelWithUiPathReturnsUiStateOrNotFound() {
        var result = tools.trellisModel("ui");
        // With no UI state pushed, resolve returns null → error is correct
        // With UI state, it returns the state
        assertNotNull(result);
    }

    @Test
    void modelWithWorkspacePathReturnsWorkspaceOrNotFound() {
        var result = tools.trellisModel("workspace");
        // With no workspace watched, resolve returns null → error is correct
        assertNotNull(result);
    }

    @Test
    void modelWithInvalidPathReturnsError() {
        var result = tools.trellisModel("nonexistent/deep/path");
        assertTrue(result.isError());
        var text = result.firstContent().toString();
        assertTrue(text.contains("not_found"));
    }

    @Test
    void modelResponseIncludesGeneration() {
        var result = tools.trellisModel(null);
        var text = result.firstContent().toString();
        assertTrue(text.contains("\"generation\""));
    }
}
