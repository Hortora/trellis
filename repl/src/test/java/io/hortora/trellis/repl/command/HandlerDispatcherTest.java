package io.hortora.trellis.repl.command;

import io.hortora.trellis.repl.ReplConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HandlerDispatcherTest {

    private static ReplConfig testConfig() {
        return new ReplConfig(System.getProperty("java.io.tmpdir"), "", "42", 8080, "paired-terminal");
    }

    @Test
    void routesShellHandler() {
        var dispatcher = new HandlerDispatcher(null, null, testConfig());
        var result = dispatcher.dispatch("shell:echo hello", new String[]{});
        assertThat(result).isInstanceOf(CommandResult.Output.class);
        assertThat(((CommandResult.Output) result).text()).contains("hello");
    }

    @Test
    void reportsErrorForUnknownPrefix() {
        var dispatcher = new HandlerDispatcher(null, null, testConfig());
        var result = dispatcher.dispatch("bogus:thing", new String[]{});
        assertThat(result).isInstanceOf(CommandResult.Error.class);
        assertThat(((CommandResult.Error) result).message()).contains("Unknown handler prefix");
    }

    @Test
    void reportsErrorForInvalidHandler() {
        var dispatcher = new HandlerDispatcher(null, null, testConfig());
        var result = dispatcher.dispatch("noprefix", new String[]{});
        assertThat(result).isInstanceOf(CommandResult.Error.class);
    }

    @Test
    void llmStartRequiresSidecar() {
        var dispatcher = new HandlerDispatcher(null, null, testConfig());
        var result = dispatcher.dispatch("llm:start", new String[]{});
        assertThat(result).isInstanceOf(CommandResult.Error.class);
        assertThat(((CommandResult.Error) result).message()).contains("Sidecar not configured");
    }

    @Test
    void sorediumRequiresBridge() {
        var dispatcher = new HandlerDispatcher(null, null, testConfig());
        var result = dispatcher.dispatch("soredium:status", new String[]{});
        assertThat(result).isInstanceOf(CommandResult.Error.class);
        assertThat(((CommandResult.Error) result).message()).contains("Soredium bridge not configured");
    }
}
