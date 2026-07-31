package io.hortora.trellis.issues;

import java.util.List;

public record GraphData(List<DagNode> nodes, List<DagEdge> edges) {}
