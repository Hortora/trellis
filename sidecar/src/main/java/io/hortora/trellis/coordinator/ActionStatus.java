package io.hortora.trellis.coordinator;

public enum ActionStatus {
    PROPOSED, APPROVED, CONFIRMING, EXECUTING,
    COMPLETED, FAILED, REJECTED, EXPIRED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == REJECTED || this == EXPIRED;
    }
}
