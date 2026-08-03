package io.hortora.trellis.coordinator;

import io.casehub.blocks.summarisation.EventAccumulator;
import io.casehub.blocks.summarisation.LevelEvent;
import io.casehub.pages.push.EventBroadcaster;
import io.casehub.platform.agent.AgentEvent;
import io.casehub.platform.agent.AgentProvider;
import io.casehub.platform.agent.AgentSessionConfig;
import io.hortora.trellis.issues.EpicAnalysis;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@ApplicationScoped
public class CoordinatorService {

    private static final Logger LOG = Logger.getLogger(CoordinatorService.class);

    @Inject EventAccumulator<CoordinatorEvent> accumulator;
    @Inject SignificanceFilter significanceFilter;
    @Inject CoordinatorContextAssembler contextAssembler;
    @Inject AgentProvider agentProvider;
    @Inject ConversationStore conversationStore;
    @Inject EventRing ring;
    @Inject EnhancedRecommendationCache recommendationCache;
    @Inject CaseRecorder caseRecorder;
    @Inject CoordinatorConfig config;
    @Inject @CoordinatorDataSourceProducer.CoordinatorDS DataSource dataSource;
    @Inject EventBroadcaster broadcaster;
    @Inject
            ActionService    actionService;


    private final Semaphore llmSemaphore = new Semaphore(1);
    private final AtomicInteger eventsProcessed = new AtomicInteger(0);
    private volatile Instant lastAdviceTime;
    private volatile EpicAnalysis latestAnalysis;
    private ScheduledExecutorService scheduler;

    CoordinatorService() {}

    static CoordinatorService forTest(CoordinatorConfig config,
                                       EventAccumulator<CoordinatorEvent> accumulator,
                                       SignificanceFilter significanceFilter,
                                       CoordinatorContextAssembler contextAssembler,
                                       AgentProvider agentProvider,
                                       ConversationStore conversationStore,
                                       EventRing ring,
                                       EnhancedRecommendationCache recommendationCache,
                                       CaseRecorder caseRecorder,
                                       DataSource dataSource) {
        var s = new CoordinatorService();
        s.config = config;
        s.accumulator = accumulator;
        s.significanceFilter = significanceFilter;
        s.contextAssembler = contextAssembler;
        s.agentProvider = agentProvider;
        s.conversationStore = conversationStore;
        s.ring = ring;
        s.recommendationCache = recommendationCache;
        s.caseRecorder = caseRecorder;
        s.dataSource = dataSource;
        return s;
    }

    @jakarta.annotation.PostConstruct
    void start() {
        if (!config.enabled()) {return;}
        contextAssembler.setActionService(actionService);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "coordinator-tick");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> tick(System.currentTimeMillis()),
                                      1, 1, TimeUnit.SECONDS);}

    @jakarta.annotation.PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    void onAnalysisRecomputed(@ObservesAsync @AnalysisRecomputed EpicAnalysis analysis) {
        this.latestAnalysis = analysis;
    }

    EpicAnalysis latestAnalysis() {
        return latestAnalysis;
    }

    void tick(long now) {
        if (!config.enabled()) return;
        if (!accumulator.shouldEmit(now)) return;

        var batch = accumulator.drain().stream()
                .map(LevelEvent::payload).toList();
        eventsProcessed.addAndGet(batch.size());

        if (!significanceFilter.isSignificant(batch)) return;
        if (!llmSemaphore.tryAcquire()) return;

        try {
            generateProactiveAdvice(batch);
        } finally {
            llmSemaphore.release();
        }
    }

    public ConversationTurn handleMessage(CoordinatorMessageRequest request) {
        conversationStore.append(request.workspace(), ConversationTurn.Role.USER, request.message());

        boolean isDirective = request.message().startsWith("/");
        var     analysis    = latestAnalysis;

        try {
            if (!llmSemaphore.tryAcquire(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Coordinator busy — try again shortly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for coordinator");
        }
        try {
            var prompt = contextAssembler.assembleInteractivePrompt(
                    request.workspace(), analysis, request.message(), isDirective);
            var taskType = isDirective
                           ? CoordinatorTask.TaskType.DIRECTIVE
                           : CoordinatorTask.TaskType.CONVERSATIONAL;
            var convDepth = conversationStore.history(request.workspace(), 1000).size();
            var task      = new CoordinatorTask(taskType, prompt.length() / 4, ring.size(), convDepth, request.workspace());

            var response = invokeLlm(prompt, task);
            conversationStore.append(request.workspace(), ConversationTurn.Role.COORDINATOR, response);

            var advice = parseAdviceResponse(response);
            if (advice != null && ActionResponseParser.parseAction(response).isPresent()) {
                persistAdviceWithAction(request.workspace(), advice, response);
                if (broadcaster != null) broadcaster.broadcast("coordinator:advice", advice);
            }

            var turns  = conversationStore.history(request.workspace(), 1);
            var result = turns.getLast();
            if (broadcaster != null) {broadcaster.broadcast("coordinator:message", result);}
            return result;
        } finally {
            llmSemaphore.release();
        }}

    public List<ConversationTurn> conversationHistory(String workspace, int maxTurns) {
        return conversationStore.history(workspace, maxTurns);
    }

    public CoordinatorStatus status() {
        return new CoordinatorStatus(
                config.enabled(), eventsProcessed.get(), lastAdviceTime,
                0, accumulator.size(), config.defaultModel());
    }

    public List<CoordinatorAdvice> recentAdvice(String workspace) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT id, workspace, epic_ref, type, title, body, action_key, created_at " +
                     "FROM coordinator_advice WHERE workspace = ? AND dismissed = 0 ORDER BY created_at DESC LIMIT 20")) {
            ps.setString(1, workspace);
            var rs = ps.executeQuery();
            var result = new ArrayList<CoordinatorAdvice>();
            while (rs.next()) {
                result.add(new CoordinatorAdvice(
                        rs.getString("id"),
                        CoordinatorAdvice.AdviceType.valueOf(rs.getString("type")),
                        rs.getString("epic_ref"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("action_key"),
                        Instant.parse(rs.getString("created_at"))));
            }
            return result;
        } catch (SQLException e) {
            LOG.warnf(e, "Failed to read advice for workspace %s", workspace);
            return List.of();
        }
    }

    public void dismissAdvice(String adviceId) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("UPDATE coordinator_advice SET dismissed = 1 WHERE id = ?")) {
            ps.setString(1, adviceId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf(e, "Failed to dismiss advice %s", adviceId);
        }
    }

    public void enhanceRecommendations(EpicAnalysis analysis, String epicRef) {
        if (!config.enabled()) {return;}
        try {
            var prompt = contextAssembler.assembleEnhancementPrompt(analysis);
            var task = new CoordinatorTask(CoordinatorTask.TaskType.RECOMMENDATION_ENHANCEMENT,
                                           prompt.length() / 4, 0, 0, epicRef);
            var response = invokeLlm(prompt, task);
            var enhanced = parseEnhancementResponse(response, analysis);
            if (enhanced != null && !enhanced.isEmpty()) {
                recommendationCache.put(epicRef, enhanced);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to enhance recommendations for %s", epicRef);
        }
    }

    private List<EnhancedRecommendation> parseEnhancementResponse(String response, EpicAnalysis analysis) {
        try {
            var recs   = analysis.recommendations();
            var result = new ArrayList<EnhancedRecommendation>();
            for (int i = 0; i < recs.size(); i++) {
                var base      = recs.get(i);
                var reasoning = extractJsonArrayString(response, i, "reasoning");
                var score     = extractJsonArrayInt(response, i, "adjustedScore", base.score());
                var factors   = List.<String>of();
                result.add(new EnhancedRecommendation(base, reasoning != null ? reasoning : base.reason(), factors, score, Instant.now()));
            }
            return result;
        } catch (Exception e) {
            LOG.debugf(e, "Could not parse enhancement response");
            return null;
        }
    }

    private String extractJsonArrayString(String json, int index, String key) {
        int pos = 0;
        for (int i = 0; i <= index; i++) {
            pos = json.indexOf('{', pos);
            if (pos < 0) {return null;}
            if (i < index) {pos++;}
        }
        int end = json.indexOf('}', pos);
        if (end < 0) {return null;}
        var obj = json.substring(pos, end + 1);
        return extractJsonString(obj, key);
    }

    private int extractJsonArrayInt(String json, int index, String key, int defaultValue) {
        var str = extractJsonArrayString(json, index, key);
        if (str == null) {return defaultValue;}
        try {return Integer.parseInt(str);} catch (NumberFormatException e) {return defaultValue;}
    }


    private void generateProactiveAdvice(List<CoordinatorEvent> batch) {
        if (latestAnalysis == null) {return;}

        var workspace = batch.stream().map(CoordinatorEvent::key).filter(k -> k != null && !k.isEmpty()).findFirst().orElse("default");
        var prompt    = contextAssembler.assembleProactivePrompt(batch, latestAnalysis);
        var task = new CoordinatorTask(CoordinatorTask.TaskType.PROACTIVE_ADVICE,
                                       prompt.length() / 4, batch.size(), 0, workspace);

        try {
            var response = invokeLlm(prompt, task);
            if (response.contains("\"none\"")) {return;}

            var advice = parseAdviceResponse(response);
            if (advice != null) {
                persistAdviceWithAction(workspace, advice, response);
                lastAdviceTime = Instant.now();
                if (broadcaster != null) {broadcaster.broadcast("coordinator:advice", advice);}
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to generate proactive advice");
        }}

    private CoordinatorAdvice parseAdviceResponse(String response) {
        try {
            if (response.indexOf("\"type\"") < 0) return null;

            var type = extractJsonString(response, "type");
            var title = extractJsonString(response, "title");
            var body = extractJsonString(response, "body");
            var actionKey = extractJsonString(response, "actionKey");

            if (type == null || title == null || body == null) return null;

            return new CoordinatorAdvice(
                    UUID.randomUUID().toString(),
                    CoordinatorAdvice.AdviceType.valueOf(type),
                    null, title, body,
                    "null".equals(actionKey) ? null : actionKey,
                    Instant.now());
        } catch (Exception e) {
            LOG.debugf(e, "Could not parse advice response");
            return null;
        }
    }

    private String extractJsonString(String json, String key) {
        var pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return null;
        int start = json.indexOf('"', colonIdx + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        while (end > 0 && json.charAt(end - 1) == '\\') {
            end = json.indexOf('"', end + 1);
        }
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private void persistAdvice(String workspace, CoordinatorAdvice advice) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO coordinator_advice (id, workspace, epic_ref, type, title, body, action_key, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, advice.id());
            ps.setString(2, workspace);
            ps.setString(3, advice.epicRef());
            ps.setString(4, advice.type().name());
            ps.setString(5, advice.title());
            ps.setString(6, advice.body());
            ps.setString(7, advice.actionKey());
            ps.setString(8, advice.timestamp().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf(e, "Failed to persist advice %s", advice.id());
        }
    }

    private void persistAdviceWithAction(String workspace, CoordinatorAdvice advice, String llmResponse) {
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (var ps = conn.prepareStatement(
                        "INSERT INTO coordinator_advice (id, workspace, epic_ref, type, title, body, action_key, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, advice.id());
                    ps.setString(2, workspace);
                    ps.setString(3, advice.epicRef());
                    ps.setString(4, advice.type().name());
                    ps.setString(5, advice.title());
                    ps.setString(6, advice.body());
                    ps.setString(7, advice.actionKey());
                    ps.setString(8, advice.timestamp().toString());
                    ps.executeUpdate();
                }
                var parsed = ActionResponseParser.parseAction(llmResponse);
                if (parsed.isPresent() && actionService != null) {
                    var a = parsed.get();
                    actionService.propose(conn, advice.id(), a.category(), a.actionType(),
                                          a.params(), a.rationale(), workspace);
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (java.sql.SQLException e) {
            LOG.warnf(e, "Failed to persist advice with action %s", advice.id());
        }
    }


    private String invokeLlm(String userPrompt, CoordinatorTask task) {
        var sessionConfig = AgentSessionConfig.of(CoordinatorPrompts.systemPrompt(), userPrompt);
        var events = agentProvider.invoke(sessionConfig)
                .collect().asList()
                .await().atMost(Duration.ofMinutes(2));

        caseRecorder.record(task, config.defaultModel());

        return events.stream()
                .filter(AgentEvent.TextDelta.class::isInstance)
                .map(AgentEvent.TextDelta.class::cast)
                .map(AgentEvent.TextDelta::text)
                .collect(Collectors.joining());
    }
}
