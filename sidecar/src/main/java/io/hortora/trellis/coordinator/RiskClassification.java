package io.hortora.trellis.coordinator;

import java.util.Set;

public final class RiskClassification {

    private static final Set<String> HIGH_RISK = Set.of(
            "lifecycle.start", "lifecycle.end", "slot.merge", "agent.stop");

    private RiskClassification() {}

    public static RiskLevel riskFor(String actionType) {
        if (HIGH_RISK.contains(actionType)) {return RiskLevel.HIGH;}
        if (actionType.startsWith("advisory.") || actionType.equals("lifecycle.pause")
            || actionType.equals("lifecycle.resume") || actionType.equals("slot.create")
            || actionType.equals("epic.setup") || actionType.equals("epic.next")
            || actionType.startsWith("agent.") && !actionType.equals("agent.stop")) {
            return RiskLevel.LOW;
        }
        return RiskLevel.HIGH;}
}
