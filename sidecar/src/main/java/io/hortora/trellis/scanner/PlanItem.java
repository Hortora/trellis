package io.hortora.trellis.scanner;

import java.util.List;

public record PlanItem(String ref, String title, boolean done, boolean active, List<PlanItem> children) {}
