package io.hortora.trellis.coordinator;

import io.hortora.trellis.issues.EpicAnalysis;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class CoordinatorContextAssembler {

    private final EventRing ring;
    private final ConversationStore conversationStore;
    private final int maxConversationTurns;

    @Inject
    public CoordinatorContextAssembler(EventRing ring, ConversationStore conversationStore,
                                       CoordinatorConfig config) {
        this(ring, conversationStore, config.maxConversationTurns());
    }

    CoordinatorContextAssembler(EventRing ring, ConversationStore conversationStore, int maxTurns) {
        this.ring = ring;
        this.conversationStore = conversationStore;
        this.maxConversationTurns = maxTurns;
    }

    public String assembleProactivePrompt(List<CoordinatorEvent> eventBatch, EpicAnalysis analysis) {
        var sb = new StringBuilder();
        appendAnalysisContext(sb, analysis);
        sb.append("\n\n## Recent Events\n");
        for (var e : eventBatch) sb.append("- ").append(formatEvent(e)).append("\n");
        return CoordinatorPrompts.proactiveTemplate(sb.toString());
    }

    public String assembleInteractivePrompt(String workspace, EpicAnalysis analysis,
                                             String userMessage, boolean isDirective) {
        var sb = new StringBuilder();
        appendAnalysisContext(sb, analysis);
        appendRecentEvents(sb);
        appendConversation(sb, workspace);
        var template = isDirective
                ? CoordinatorPrompts.directiveTemplate(userMessage)
                : CoordinatorPrompts.conversationalTemplate(userMessage);
        return sb + "\n\n" + template;
    }

    public String assembleEnhancementPrompt(EpicAnalysis analysis) {
        var recs = analysis.recommendations().stream()
                .map(r -> "%s: %s (score=%d, type=%s, reason=%s)"
                        .formatted(r.key(), r.title(), r.score(), r.type(), r.reason()))
                .collect(Collectors.joining("\n"));
        var sb = new StringBuilder();
        appendAnalysisContext(sb, analysis);
        return sb + "\n\n" + CoordinatorPrompts.enhancementTemplate(recs);
    }

    private void appendAnalysisContext(StringBuilder sb, EpicAnalysis analysis) {
        if (analysis == null) {
            sb.append("## Epic Overview\nNo analysis available yet.\n");
            return;
        }
        sb.append("## Epic Overview\n");
        var k = analysis.kpis();
        sb.append("Total: %d, Open: %d, Closed: %d, Critical path: %d, Bottlenecks: %d\n"
                          .formatted(k.total(), k.open(), k.closed(), k.criticalPathLength(), k.bottleneckCount()));
        sb.append("\n## Recommendations\n");
        for (var r : analysis.recommendations()) {
            sb.append("- %s: %s (score=%d, %s) — %s\n"
                              .formatted(r.key(), r.title(), r.score(), r.type(), r.reason()));
        }
    }

    private void appendRecentEvents(StringBuilder sb) {
        var events = ring.snapshot();
        if (!events.isEmpty()) {
            sb.append("\n## Recent Events\n");
            for (var e : events) sb.append("- ").append(formatEvent(e)).append("\n");
        }
    }

    private void appendConversation(StringBuilder sb, String workspace) {
        var turns = conversationStore.history(workspace, maxConversationTurns);
        if (!turns.isEmpty()) {
            sb.append("\n## Conversation History\n");
            for (var t : turns) sb.append("[%s] %s\n".formatted(t.role(), t.content()));
        }
    }

    private String formatEvent(CoordinatorEvent event) {
        return switch (event) {
            case CoordinatorEvent.WorkspaceChangedEvent e -> "WorkspaceChanged: " + e.workspaceRoot();
            case CoordinatorEvent.AnalysisEvent e -> "AnalysisRecomputed: " + e.epicRef() + " unblocked=" + e.newlyUnblocked();
            case CoordinatorEvent.IssueEvent e -> "IssueEvent: " + e.issueKey() + " action=" + e.action();
        };
    }
}
