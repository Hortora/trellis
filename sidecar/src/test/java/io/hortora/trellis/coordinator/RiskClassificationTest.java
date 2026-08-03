package io.hortora.trellis.coordinator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RiskClassificationTest {

    @Test
    void highRiskActionsIdentified() {
        assertEquals(RiskLevel.HIGH, RiskClassification.riskFor("lifecycle.start"));
        assertEquals(RiskLevel.HIGH, RiskClassification.riskFor("lifecycle.end"));
        assertEquals(RiskLevel.HIGH, RiskClassification.riskFor("slot.merge"));
        assertEquals(RiskLevel.HIGH, RiskClassification.riskFor("agent.stop"));
    }

    @Test
    void lowRiskActionsIdentified() {
        assertEquals(RiskLevel.LOW, RiskClassification.riskFor("lifecycle.pause"));
        assertEquals(RiskLevel.LOW, RiskClassification.riskFor("lifecycle.resume"));
        assertEquals(RiskLevel.LOW, RiskClassification.riskFor("slot.create"));
        assertEquals(RiskLevel.LOW, RiskClassification.riskFor("epic.setup"));
        assertEquals(RiskLevel.LOW, RiskClassification.riskFor("epic.next"));
        assertEquals(RiskLevel.LOW, RiskClassification.riskFor("agent.start"));
        assertEquals(RiskLevel.LOW, RiskClassification.riskFor("agent.pause"));
        assertEquals(RiskLevel.LOW, RiskClassification.riskFor("agent.resume"));
        assertEquals(RiskLevel.LOW, RiskClassification.riskFor("agent.refresh"));
        assertEquals(RiskLevel.LOW, RiskClassification.riskFor("advisory.prioritise"));
        assertEquals(RiskLevel.LOW, RiskClassification.riskFor("advisory.investigate"));
    }

    @Test
    void unknownTypeDefaultsToHigh() {
        assertEquals(RiskLevel.HIGH, RiskClassification.riskFor("unknown.type"));
    }
}
