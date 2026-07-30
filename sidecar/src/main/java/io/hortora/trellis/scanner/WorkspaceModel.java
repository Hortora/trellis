package io.hortora.trellis.scanner;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record WorkspaceModel(
        Path root,
        Instant scannedAt,
        List<RepoInfo> repos,
        List<SlotInfo> slots,
        List<PauseEntry> pauses,
        List<EpicInfo> epics
) {}
