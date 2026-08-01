package io.hortora.trellis.coordinator;

public final class CoordinatorPrompts {

    private CoordinatorPrompts() {}

    public static String systemPrompt() {
        return """
                You are the Trellis Coordinator — an advisor for epic delivery. You observe \
                workspace activity, understand dependency graphs and critical paths, and help \
                developers make better decisions about what to work on and why.

                You have access to:
                - The epic's dependency graph and critical path analysis
                - Algorithmic recommendations scored by cascade unlock potential
                - Recent workspace activity (lifecycle operation notifications)
                - GitHub issue state and metadata

                Be specific and actionable. Reference issue numbers. Explain trade-offs \
                quantitatively when possible. When re-ranking, explain what context the \
                algorithm couldn't see.""";
    }

    public static String proactiveTemplate(String context) {
        return context + """

                \nThe events above just occurred. Based on the current epic state, are any of \
                these significant enough to warrant advice? If yes, respond with JSON:
                {"type": "INSIGHT|WARNING|SUGGESTION|STATUS", "title": "<under 80 chars>", \
                "body": "<markdown>", "actionKey": "<issue key or null>"}

                If nothing warrants advice, respond with: {"none": true}""";
    }

    public static String conversationalTemplate(String userMessage) {
        return """
                The developer asks:
                %s

                Answer using the full context above. Be direct and specific.""".formatted(userMessage);
    }

    public static String directiveTemplate(String userMessage) {
        return """
                The developer requests:
                %s

                Execute this directive using the context above. If it requires re-analysis, \
                describe the modified state and its implications. If it requires producing \
                an artifact, produce it in full.""".formatted(userMessage);
    }

    public static String enhancementTemplate(String recommendations) {
        return """
                Here are the algorithmic recommendations for this epic:
                %s

                For each recommendation, provide a JSON array where each element has: \
                reasoning (multi-paragraph string), contextFactors (string array), \
                adjustedScore (integer, preserve original if no adjustment needed).""".formatted(recommendations);
    }
}
