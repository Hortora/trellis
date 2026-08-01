package io.hortora.trellis.coordinator;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "trellis.coordinator")
public interface CoordinatorConfig {
    @WithDefault("true")
    boolean enabled();

    @WithDefault("30000")
    long windowTimeMs();

    @WithDefault("20")
    int windowCount();

    @WithDefault("50")
    int maxConversationTurns();

    @WithDefault("3")
    int maxAgentIterations();

    @WithDefault("claude-sonnet-5")
    String defaultModel();

    @WithDefault("${user.home}/.trellis/coordinator.db")
    String dbPath();
}
