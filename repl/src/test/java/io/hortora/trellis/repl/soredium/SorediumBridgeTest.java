package io.hortora.trellis.repl.soredium;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SorediumBridgeTest {

    @Test
    void parsesJsonLinesOutput() {
        var lines = List.of(
                """
                {"type":"StepProgress","data":{"command":"status","step":"resolve","detail":"resolving context"}}""".strip(),
                """
                {"type":"StatusReady","data":{"branch":"main","state":"idle","on_main":true}}""".strip()
        );
        var events = SorediumBridge.parseEvents(lines);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("StepProgress");
        assertThat(events.get(1).type()).isEqualTo("StatusReady");
        assertThat(events.get(1).data().get("branch")).isEqualTo("main");
        assertThat(events.get(1).data().get("on_main")).isEqualTo(true);
    }

    @Test
    void handlesEmptyLines() {
        var events = SorediumBridge.parseEvents(List.of("", "  "));
        assertThat(events).isEmpty();
    }

    @Test
    void handlesMalformedJson() {
        var events = SorediumBridge.parseEvents(List.of("not json"));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("ParseError");
    }

    @Test
    void parsesCommandFailedEvent() {
        var lines = List.of(
                """
                {"type":"CommandFailed","data":{"command":"start","step":null,"error":"not_idle","detail":"Cannot start","recoverable":false}}""".strip()
        );
        var events = SorediumBridge.parseEvents(lines);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("CommandFailed");
        assertThat(events.get(0).data().get("error")).isEqualTo("not_idle");
        assertThat(events.get(0).data().get("recoverable")).isEqualTo(false);
    }
}
