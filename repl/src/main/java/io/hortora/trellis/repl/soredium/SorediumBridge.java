package io.hortora.trellis.repl.soredium;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SorediumBridge {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String sorediumPath;
    private final String cwd;

    public SorediumBridge(String sorediumPath, String cwd) {
        this.sorediumPath = sorediumPath;
        this.cwd = cwd;
    }

    public List<SorediumEvent> execute(String command, Map<String, String> kwargs) throws Exception {
        var fullKwargs = new HashMap<>(kwargs);
        fullKwargs.put("cwd", cwd);
        var kwargsJson = MAPPER.writeValueAsString(fullKwargs);

        var pb = new ProcessBuilder("python3", "-m", "cli", command, kwargsJson)
                .directory(new File(sorediumPath))
                .redirectErrorStream(false);
        var process = pb.start();

        var lines = new BufferedReader(new InputStreamReader(process.getInputStream()))
                .lines().toList();
        process.waitFor();

        return parseEvents(lines);
    }

    @SuppressWarnings("unchecked")
    public static List<SorediumEvent> parseEvents(List<String> lines) {
        return lines.stream()
                .filter(l -> !l.isBlank())
                .map(line -> {
                    try {
                        var map = MAPPER.readValue(line, new TypeReference<Map<String, Object>>() {});
                        var type = (String) map.get("type");
                        var data = (Map<String, Object>) map.getOrDefault("data", Map.of());
                        return new SorediumEvent(type, data);
                    } catch (Exception e) {
                        return new SorediumEvent("ParseError",
                                Map.of("raw", line, "error", e.getMessage()));
                    }
                })
                .toList();
    }
}
