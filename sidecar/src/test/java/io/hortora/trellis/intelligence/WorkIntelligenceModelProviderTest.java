package io.hortora.trellis.intelligence;

import io.casehub.ras.api.ActiveSituation;
import io.casehub.ras.api.SituationSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkIntelligenceModelProviderTest {

    @Test
    void mapsHighConfidenceToActionNeeded() {
        assertEquals("ACTION_NEEDED", WorkIntelligenceModelProvider.mapSeverity(0.85));
    }

    @Test
    void mapsMediumConfidenceToAttention() {
        assertEquals("ATTENTION", WorkIntelligenceModelProvider.mapSeverity(0.55));
    }

    @Test
    void mapsLowConfidenceToInfo() {
        assertEquals("INFO", WorkIntelligenceModelProvider.mapSeverity(0.3));
    }

    @Test
    void mapsBoundaryAt07ToAttention() {
        assertEquals("ATTENTION", WorkIntelligenceModelProvider.mapSeverity(0.7));
    }

    @Test
    void mapsBoundaryAt04ToAttention() {
        assertEquals("ATTENTION", WorkIntelligenceModelProvider.mapSeverity(0.4));
    }

    @Test
    void domainIsIntelligence() {
        var provider = new WorkIntelligenceModelProvider(stubSource(List.of()));
        assertEquals("intelligence", provider.domain());
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryContainsFindingsFromActiveSituations() {
        var situations = List.of(
                new ActiveSituation("stalled-work", "issue-42", "trellis",
                        0.85, Map.of("branch", "issue-42-feat", "lastEventDaysAgo", 14, "issueNumber", 42),
                        Instant.now().minusSeconds(86400 * 14), Instant.now(), 1)
        );
        var provider = new WorkIntelligenceModelProvider(stubSource(situations));

        var result = (Map<String, Object>) provider.summary();
        var summary = (Map<String, Object>) result.get("summary");
        var findings = (List<Map<String, Object>>) result.get("findings");

        assertEquals(1, summary.get("actionNeeded"));
        assertEquals(0, summary.get("attention"));
        assertEquals(0, summary.get("info"));
        assertEquals(1, findings.size());

        var finding = findings.getFirst();
        assertEquals("stalled-work", finding.get("facet"));
        assertEquals("ACTION_NEEDED", finding.get("severity"));
        assertEquals(0.85, finding.get("confidence"));
        assertNotNull(finding.get("summary"));
        assertNotNull(finding.get("suggestion"));
        assertTrue(finding.get("summary").toString().contains("idle"));
        assertTrue(finding.get("suggestion").toString().contains("42"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryReturnsEmptyWhenNoSituations() {
        var provider = new WorkIntelligenceModelProvider(stubSource(List.of()));

        var result = (Map<String, Object>) provider.summary();
        var summary = (Map<String, Object>) result.get("summary");
        var findings = (List<?>) result.get("findings");

        assertEquals(0, summary.get("actionNeeded"));
        assertEquals(0, summary.get("attention"));
        assertEquals(0, summary.get("info"));
        assertTrue(findings.isEmpty());
    }

    private SituationSource stubSource(List<ActiveSituation> situations) {
        return tenancyId -> situations;
    }
}
