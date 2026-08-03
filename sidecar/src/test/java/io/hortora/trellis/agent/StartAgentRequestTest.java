package io.hortora.trellis.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartAgentRequestTest {

    @Test
    void freshSessionNoPrompt() {
        var req = new StartAgentRequest(false, null);
        assertFalse(req.resume());
        assertNull(req.prompt());
    }

    @Test
    void freshSessionWithPrompt() {
        var req = new StartAgentRequest(false, "Fix the login bug");
        assertEquals("Fix the login bug", req.prompt());
    }

    @Test
    void resumeSession() {
        var req = new StartAgentRequest(true, null);
        assertTrue(req.resume());
    }

    @Test
    void resumeWithPromptThrows() {
        var req = new StartAgentRequest(true, "some prompt");
        assertThrows(IllegalArgumentException.class, req::validate);
    }
}
