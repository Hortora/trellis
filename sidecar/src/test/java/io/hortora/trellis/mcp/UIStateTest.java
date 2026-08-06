package io.hortora.trellis.mcp;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class UIStateTest {

    @Inject
    UIStateStore store;

    @Inject
    UIStateModelProvider uiProvider;

    @Test
    void postAndRetrieveUIState() {
        var state = """
                {"activePanel": "workspace", "panels": {"workspace": {"visible": true, "content": {}, "lastPushed": %d}}}
                """.formatted(System.currentTimeMillis());
        given()
                .contentType("application/json")
                .body(state)
                .when().post("/api/model/ui-state")
                .then().statusCode(204);

        var current = store.current();
        assertNotNull(current);
        assertEquals("workspace", current.get("activePanel"));
    }

    @Test
    void rejectOversizedContent() {
        var huge = "{\"activePanel\": \"x\", \"panels\": {\"x\": {\"visible\": true, \"content\": \"" + "a".repeat(70000) + "\", \"lastPushed\": 1}}}";
        given()
                .contentType("application/json")
                .body(huge)
                .when().post("/api/model/ui-state")
                .then().statusCode(413);
    }

    @Test
    void rejectInvalidJson() {
        given()
                .contentType("application/json")
                .body("not json")
                .when().post("/api/model/ui-state")
                .then().statusCode(400);
    }

    @Test
    void uiProviderDomainIsUi() {
        assertEquals("ui", uiProvider.domain());
    }

    @Test
    @SuppressWarnings("unchecked")
    void uiProviderSummaryReflectsState() {
        store.update(Map.of(
                "activePanel", "dashboard",
                "panels", Map.of("dashboard", Map.of(
                        "visible", true,
                        "content", Map.of(),
                        "lastPushed", System.currentTimeMillis()
                ))
        ));

        var summary = uiProvider.summary();
        assertNotNull(summary);
        assertInstanceOf(Map.class, summary);
        var map = (Map<String, Object>) summary;
        assertEquals("dashboard", map.get("activePanel"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void uiProviderSummaryWhenNoStateShowsDisconnected() {
        store.clear();
        var summary = uiProvider.summary();
        assertInstanceOf(Map.class, summary);
        var map = (Map<String, Object>) summary;
        assertEquals(false, map.get("connected"));
    }

    @Test
    void uiProviderActionsIsEmpty() {
        var actions = uiProvider.actionsFor("panel");
        assertTrue(actions.isEmpty());
    }

    @Test
    void uiProviderResolveDockBar() {
        store.update(Map.of("activePanel", "artifacts", "panels", Map.of()));
        var result = uiProvider.resolve("dock-bar");
        assertEquals("artifacts", result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void uiProviderResolvePanelByName() {
        var panelState = Map.of("visible", true, "content", Map.of("test", "value"), "lastPushed", System.currentTimeMillis());
        store.update(Map.of("activePanel", "workspace", "panels", Map.of("workspace", panelState)));
        var result = uiProvider.resolve("panels/workspace");
        assertNotNull(result);
        assertInstanceOf(Map.class, result);
    }
}
