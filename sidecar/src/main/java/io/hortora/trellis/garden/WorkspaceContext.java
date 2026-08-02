package io.hortora.trellis.garden;

import java.util.List;

public record WorkspaceContext(int slotNumber, String slotStatus, List<String> repos) {}
