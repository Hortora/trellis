package io.hortora.trellis.mcp;

import java.util.List;

public interface ModelProvider {
    String domain();
    Object summary();
    Object resolve(String subpath);
    List<ActionDescriptor> actionsFor(String nodeType);
}
