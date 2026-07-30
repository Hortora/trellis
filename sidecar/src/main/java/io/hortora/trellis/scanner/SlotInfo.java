package io.hortora.trellis.scanner;

import java.nio.file.Path;
import java.util.List;

public record SlotInfo(
        int number,
        Path path,
        String issue,
        SlotStatus status,
        boolean isEpic,
        List<String> repos
) {}
