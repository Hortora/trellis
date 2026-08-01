package io.hortora.trellis.coordinator;

import io.hortora.trellis.issues.EpicAnalysis;
import io.hortora.trellis.issues.EpicKpis;
import io.hortora.trellis.issues.Recommendation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatorContextAssemblerTest {

    @TempDir Path tmpDir;
    private EventRing ring;
    private ConversationStore store;
    private CoordinatorContextAssembler assembler;

    @BeforeEach
    void setUp() throws Exception {
        ring = new EventRing(16);
        var ds = createDataSource();
        new CoordinatorSchemaManager().initialize(ds);
        store = ConversationStore.forTest(ds);
        assembler = new CoordinatorContextAssembler(ring, store, 50);
    }

    @Test
    void proactivePromptIncludesEventBatchAndAnalysis() {
        var events = List.<CoordinatorEvent>of(
                new CoordinatorEvent.AnalysisEvent(Instant.now(), "k", "owner/repo#1", List.of("owner/repo#5")));
        var analysis = testAnalysis();

        var prompt = assembler.assembleProactivePrompt(events, analysis);

        assertTrue(prompt.contains("AnalysisRecomputed"), "should contain formatted event");
        assertTrue(prompt.contains("owner/repo#1"), "should contain epic ref");
        assertTrue(prompt.contains("owner/repo#5"), "should contain unblocked issue");
        assertTrue(prompt.contains("Total: 10"), "should contain KPIs");
        assertTrue(prompt.contains("warrant advice"), "should contain proactive template");
    }

    @Test
    void interactivePromptIncludesRingEventsConversationAndMessage() {
        ring.add(new CoordinatorEvent.WorkspaceChangedEvent(Instant.now(), "ws", Path.of("/tmp/ws")));
        store.append("ws1", ConversationTurn.Role.USER, "prior question");
        store.append("ws1", ConversationTurn.Role.COORDINATOR, "prior answer");

        var prompt = assembler.assembleInteractivePrompt("ws1", testAnalysis(), "new question", false);

        assertTrue(prompt.contains("WorkspaceChanged"), "should contain ring event");
        assertTrue(prompt.contains("prior question"), "should contain conversation history");
        assertTrue(prompt.contains("prior answer"), "should contain coordinator response");
        assertTrue(prompt.contains("new question"), "should contain user message");
        assertTrue(prompt.contains("The developer asks"), "should use conversational template");
    }

    @Test
    void interactiveDirectiveUsesDirectiveTemplate() {
        var prompt = assembler.assembleInteractivePrompt("ws1", testAnalysis(), "/rerank by risk", true);

        assertTrue(prompt.contains("/rerank by risk"), "should contain directive");
        assertTrue(prompt.contains("The developer requests"), "should use directive template");
        assertFalse(prompt.contains("The developer asks"), "should not use conversational template");
    }

    @Test
    void enhancementPromptFormatsRecommendations() {
        var analysis = testAnalysis();

        var prompt = assembler.assembleEnhancementPrompt(analysis);

        assertTrue(prompt.contains("owner/repo#3"), "should contain recommendation key");
        assertTrue(prompt.contains("Fix auth"), "should contain recommendation title");
        assertTrue(prompt.contains("score=100"), "should contain score");
        assertTrue(prompt.contains("CRITICAL_PATH"), "should contain type");
        assertTrue(prompt.contains("adjustedScore"), "should contain enhancement template");
    }

    @Test
    void interactivePromptOmitsEmptySections() {
        var prompt = assembler.assembleInteractivePrompt("ws1", testAnalysis(), "hello", false);

        assertFalse(prompt.contains("## Recent Events"), "should omit empty events section");
        assertFalse(prompt.contains("## Conversation History"), "should omit empty conversation section");
    }

    @Test
    void proactivePromptFormatsAllEventTypes() {
        var events = List.<CoordinatorEvent>of(
                new CoordinatorEvent.WorkspaceChangedEvent(Instant.now(), "ws", Path.of("/tmp/ws")),
                new CoordinatorEvent.AnalysisEvent(Instant.now(), "k", "ref", List.of()),
                new CoordinatorEvent.IssueEvent(Instant.now(), "k", "owner/repo#7", "cache-refreshed"));

        var prompt = assembler.assembleProactivePrompt(events, testAnalysis());

        assertTrue(prompt.contains("WorkspaceChanged: /tmp/ws"), "should format workspace event");
        assertTrue(prompt.contains("AnalysisRecomputed: ref"), "should format analysis event");
        assertTrue(prompt.contains("IssueEvent: owner/repo#7 action=cache-refreshed"), "should format issue event");
    }

    @Test
    void interactivePromptRespectsConversationTurnLimit() throws Exception {
        var ds = createDataSource();
        new CoordinatorSchemaManager().initialize(ds);
        var limitedStore     = ConversationStore.forTest(ds);
        var limitedAssembler = new CoordinatorContextAssembler(ring, limitedStore, 2);

        for (int i = 0; i < 5; i++) {
            limitedStore.append("ws1", ConversationTurn.Role.USER, "msg " + i);
        }

        var prompt = limitedAssembler.assembleInteractivePrompt("ws1", testAnalysis(), "current", false);

        assertTrue(prompt.contains("msg 3"), "should contain second-to-last turn");
        assertTrue(prompt.contains("msg 4"), "should contain last turn");
        assertFalse(prompt.contains("msg 0"), "should not contain oldest turn");
        assertFalse(prompt.contains("msg 2"), "should not contain turn beyond limit");
    }

    private EpicAnalysis testAnalysis() {
        return new EpicAnalysis(
                List.of(),
                null,
                new EpicKpis(10, 6, 4, 3, 5, 2, 4),
                List.of(new Recommendation("owner/repo#3", "Fix auth", Recommendation.Type.CRITICAL_PATH, 100, "blocks 3 issues")),
                List.of(),
                List.of()
        );
    }

    private DataSource createDataSource() {
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDir.resolve("test-" + System.nanoTime() + ".db"));
        return ds;
    }
}
