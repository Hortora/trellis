package io.hortora.trellis.coordinator;

import java.util.Map;

public record ActionResult(boolean success, int exitCode, Map<String, String> output, String detail) {

    public static ActionResult ok(String detail) {
        return new ActionResult(true, 0, Map.of(), detail);
    }

    public static ActionResult fail(String detail) {
        return new ActionResult(false, -1, Map.of(), detail);
    }
}
