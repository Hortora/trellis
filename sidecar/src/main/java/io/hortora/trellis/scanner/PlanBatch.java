package io.hortora.trellis.scanner;

import java.util.List;

public record PlanBatch(String name, List<PlanItem> items) {}
