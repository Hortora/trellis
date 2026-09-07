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
) {
    public WorkspaceModel withRepos(List<RepoInfo> repos) {
        return new WorkspaceModel(root, scannedAt, repos, slots, pauses, epics);
    }

    public WorkspaceModel withSlots(List<SlotInfo> slots) {
        return new WorkspaceModel(root, scannedAt, repos, slots, pauses, epics);
    }

    public WorkspaceModel withPausesAndEpics(List<PauseEntry> pauses, List<EpicInfo> epics) {
        return new WorkspaceModel(root, scannedAt, repos, slots, pauses, epics);
    }
}
