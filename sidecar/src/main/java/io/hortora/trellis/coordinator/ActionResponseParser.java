package io.hortora.trellis.coordinator;

import jakarta.json.Json;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class ActionResponseParser {

    private ActionResponseParser() {}

    public record ParsedAction(
            ActionCategory category, String actionType,
            Map<String, String> params, String rationale) {}

    public static Optional<ParsedAction> parseAction(String response) {
        try (var reader = Json.createReader(new StringReader(response))) {
            var root = reader.readObject();
            if (!root.containsKey("action")) return Optional.empty();
            var action = root.getJsonObject("action");
            var category = ActionCategory.valueOf(action.getString("category"));
            var actionType = action.getString("actionType");
            var rationale = action.getString("rationale", "");
            var paramsObj = action.getJsonObject("params");
            var params = new HashMap<String, String>();
            for (var key : paramsObj.keySet()) {
                params.put(key, paramsObj.getString(key));
            }
            return Optional.of(new ParsedAction(category, actionType, params, rationale));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
