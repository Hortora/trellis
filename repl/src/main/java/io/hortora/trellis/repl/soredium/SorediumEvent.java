package io.hortora.trellis.repl.soredium;

import java.util.Map;

public record SorediumEvent(String type, Map<String, Object> data) {}
