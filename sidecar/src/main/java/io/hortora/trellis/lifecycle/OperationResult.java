package io.hortora.trellis.lifecycle;

import java.util.Map;

public record OperationResult(
        boolean success,
        int exitCode,
        Map<String, String> output,
        String stderr
) {}
