package io.hortora.trellis.issues;

import java.util.List;

public record BatchInfo(int batch, String label, String status, List<String> issues) {}
