package io.hortora.trellis.repl;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ReplConfigTest {

    @Test
    void parsesAllArgs() {
        var args = new String[]{
                "--repo", "/path/to/repo",
                "--slot", "slot-1",
                "--issue", "75",
                "--sidecar-port", "8080",
                "--paired-terminal", "trellis-engine-75"
        };
        var config = ReplConfig.fromArgs(args);
        assertThat(config.repo()).isEqualTo("/path/to/repo");
        assertThat(config.slot()).isEqualTo("slot-1");
        assertThat(config.issue()).isEqualTo("75");
        assertThat(config.sidecarPort()).isEqualTo(8080);
        assertThat(config.pairedTerminal()).isEqualTo("trellis-engine-75");
    }

    @Test
    void defaultsForMissingArgs() {
        var config = ReplConfig.fromArgs(new String[]{});
        assertThat(config.repo()).isEmpty();
        assertThat(config.slot()).isEmpty();
        assertThat(config.issue()).isEmpty();
        assertThat(config.sidecarPort()).isZero();
        assertThat(config.pairedTerminal()).isEmpty();
    }

    @Test
    void parsesPartialArgs() {
        var args = new String[]{"--repo", "/my/repo", "--issue", "42"};
        var config = ReplConfig.fromArgs(args);
        assertThat(config.repo()).isEqualTo("/my/repo");
        assertThat(config.issue()).isEqualTo("42");
        assertThat(config.slot()).isEmpty();
        assertThat(config.sidecarPort()).isZero();
    }
}
