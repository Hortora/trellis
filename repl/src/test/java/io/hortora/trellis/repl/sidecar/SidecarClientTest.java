package io.hortora.trellis.repl.sidecar;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SidecarClientTest {

    @Test
    void constructsTerminalUrl() {
        var client = new SidecarClient(8080);
        assertThat(client.terminalUrl("engine-42"))
                .isEqualTo("http://localhost:8080/api/terminals/engine-42");
    }

    @Test
    void constructsAgentStartUrl() {
        var client = new SidecarClient(9090);
        assertThat(client.agentStartUrl("repl-75"))
                .isEqualTo("http://localhost:9090/api/terminals/repl-75/agent/start");
    }

    @Test
    void constructsInputUrl() {
        var client = new SidecarClient(8080);
        assertThat(client.inputUrl("engine-42"))
                .isEqualTo("http://localhost:8080/api/terminals/engine-42/input");
    }

    @Test
    void constructsAgentStopUrl() {
        var client = new SidecarClient(8080);
        assertThat(client.agentStopUrl("engine-42"))
                .isEqualTo("http://localhost:8080/api/terminals/engine-42/agent/stop");
    }
}
