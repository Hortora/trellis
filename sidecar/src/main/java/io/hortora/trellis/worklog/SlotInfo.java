package io.hortora.trellis.worklog;

public record SlotInfo(long id, int slotNumber, String familyRoot, String state,
                       String createdAt, String archivedAt) {}
