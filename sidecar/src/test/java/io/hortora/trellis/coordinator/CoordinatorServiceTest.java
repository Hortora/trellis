package io.hortora.trellis.coordinator;

import io.casehub.blocks.summarisation.EventAccumulator;
import io.casehub.blocks.summarisation.EventLevel;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.blocks.summarisation.WindowPolicy;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSession;
import io.casehub.platform.agent.AgentSessionConfig;
import io.casehub.platform.agent.AgentSessionInit;
import io.hortora.trellis.issues.EpicAnalysis;
import io.hortora.trellis.issues.EpicKpis;
import io.hortora.trellis.issues.Recommendation;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatorServiceTest {

    @TempDir Path tmpDir;
    private TestAgentProvider agentProvider;
    private EventAccumulator<CoordinatorEvent> accumulator;
    private CoordinatorService service;

    @BeforeEach
    void setUp() throws SQLException {
        agentProvider = new TestAgentProvider("coordinator response");
        accumulator = new EventAccumulator<>(new WindowPolicy(1000, 20));
        var ring = new EventRing(16);
        var ds = createDataSource();
        new CoordinatorSchemaManager().initialize(ds);
        var store = ConversationStore.forTest(ds);
        var caseRecorder = CaseRecorder.forTest(ds);
        var assembler = new CoordinatorContextAssembler(ring, store, 50);
        var filter = new SignificanceFilter();
        var cache = new EnhancedRecommendationCache();
        var config = new TestConfig(true, 1000, 20, 50, 3, "claude-sonnet-5", "");

        service = CoordinatorService.forTest(
                config, accumulator, filter, assembler, agentProvider,
                store, ring, cache, caseRecorder, ds);
    }

    @Test
    void tickSkipsWhenAccumulatorEmpty() {
        service.tick(System.currentTimeMillis());
        assertEquals(0, agentProvider.invocationCount, "should not invoke LLM when no events");
    }

    @Test
    void tickSkipsWhenBatchNotSignificant() {
        var event = new CoordinatorEvent.WorkspaceChangedEvent(
                Instant.now(), "ws", Path.of("/tmp"));
        accumulator.collect(new LevelEvent<>(event, System.currentTimeMillis(), LEVEL));
        service.tick(System.currentTimeMillis() + 2000);

        assertEquals(0, agentProvider.invocationCount,
                "should not invoke LLM for non-significant events");
    }

    @Test
    void tickInvokesLlmWhenSignificant() {
        service.onAnalysisRecomputed(testAnalysis());
        var event = new CoordinatorEvent.AnalysisEvent(
                Instant.now(), "k", "owner/repo#1", List.of("owner/repo#5"));
        accumulator.collect(new LevelEvent<>(event, System.currentTimeMillis(), LEVEL));
        service.tick(System.currentTimeMillis() + 2000);

        assertEquals(1, agentProvider.invocationCount,
                "should invoke LLM for significant events");
    }

    @Test
    void tickSkipsWhenDisabled() throws Exception {
        var ds = createDataSource();
        new CoordinatorSchemaManager().initialize(ds);
        var disabledConfig = new TestConfig(false, 1000, 20, 50, 3, "model", "");
        var disabledService = CoordinatorService.forTest(
                disabledConfig, accumulator, new SignificanceFilter(),
                new CoordinatorContextAssembler(new EventRing(16), ConversationStore.forTest(ds), 50),
                agentProvider, ConversationStore.forTest(ds), new EventRing(16),
                new EnhancedRecommendationCache(), CaseRecorder.forTest(ds), ds);

        var event = new CoordinatorEvent.AnalysisEvent(
                Instant.now(), "k", "ref", List.of("owner/repo#5"));
        accumulator.collect(new LevelEvent<>(event, System.currentTimeMillis(), LEVEL));

        disabledService.tick(System.currentTimeMillis() + 2000);
        assertEquals(0, agentProvider.invocationCount, "should skip when disabled");
    }

    @Test
    void handleMessageReturnsCoordinatorResponse() {
        service.onAnalysisRecomputed(testAnalysis());
        var request = new CoordinatorMessageRequest("ws1", "owner/repo#2", "What should I work on?");
        var turn = service.handleMessage(request);

        assertEquals(ConversationTurn.Role.COORDINATOR, turn.role());
        assertEquals("coordinator response", turn.content());
    }

    @Test
    void handleMessageStoresConversation() {
        service.onAnalysisRecomputed(testAnalysis());
        service.handleMessage(new CoordinatorMessageRequest("ws1", "ref", "hello"));

        var turns = service.conversationHistory("ws1", 50);
        assertEquals(2, turns.size(), "should store user message and coordinator response");
        assertEquals(ConversationTurn.Role.USER, turns.get(0).role());
        assertEquals("hello", turns.get(0).content());
        assertEquals(ConversationTurn.Role.COORDINATOR, turns.get(1).role());
    }

    @Test
    void handleMessageUsesDirectiveTemplateForSlashPrefix() {
        service.onAnalysisRecomputed(testAnalysis());
        service.handleMessage(new CoordinatorMessageRequest("ws1", "ref", "/rerank by risk"));

        assertEquals(1, agentProvider.invocationCount);
        assertTrue(agentProvider.lastUserPrompt.contains("The developer requests"),
                "should use directive template for /commands");
    }

    @Test
    void onAnalysisRecomputedUpdatesLatest() {
        assertNull(service.latestAnalysis(), "should be null initially");
        var analysis = testAnalysis();
        service.onAnalysisRecomputed(analysis);
        assertSame(analysis, service.latestAnalysis(), "should update to latest");
    }

    @Test
    void statusReturnsCurrentState() {
        var status = service.status();
        assertTrue(status.enabled());
        assertEquals(0, status.eventsProcessed());
        assertNull(status.lastAdviceTime());
        assertEquals("claude-sonnet-5", status.currentModel());
    }

    private EpicAnalysis testAnalysis() {
        return new EpicAnalysis(
                List.of(), null,
                new EpicKpis(10, 6, 4, 3, 5, 2, 4),
                List.of(new Recommendation("owner/repo#3", "Fix auth",
                        Recommendation.Type.CRITICAL_PATH, 100, "blocks 3 issues")),
                List.of(), List.of());
    }

    private DataSource createDataSource() {
        var ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + tmpDir.resolve("test-" + System.nanoTime() + ".db"));
        return ds;
    }

    private static final EventLevel LEVEL = new EventLevel("coordinator", 0);

    static class TestAgentProvider implements AgentProvider {
        private final String response;
        int invocationCount = 0;
        String lastUserPrompt;

        TestAgentProvider(String response) {
            this.response = response;
        }

        @Override
        public Multi<AgentEvent> invoke(AgentSessionConfig config) {
            invocationCount++;
            lastUserPrompt = config.userPrompt();
            return Multi.createFrom().items(
                    new AgentEvent.TextDelta(response),
                    new AgentEvent.InvocationComplete(100, 50, 0, 0, 0, 0.001, 500L, 400L, "test", 1, false));
        }

        @Override
        public AgentSession openSession(AgentSessionInit init) {
            throw new UnsupportedOperationException();
        }
    }

    record TestConfig(boolean enabled, long windowTimeMs, int windowCount,
                      int maxConversationTurns, int maxAgentIterations,
                      String defaultModel, String dbPath) implements CoordinatorConfig {}
}
