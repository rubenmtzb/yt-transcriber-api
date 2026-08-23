package io.github.rubenix.yttranscriber.integration.youtube;

import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YtDlpProcessRunnerTest {

    private final YtDlpProcessRunner runner = new YtDlpProcessRunner();

    @Test
    void capturesStdoutAndExitCodeOfASuccessfulCommand() {
        var result = runner.run(List.of("sh", "-c", "echo hello"), Duration.ofSeconds(5));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello");
    }

    @Test
    void capturesANonZeroExitCode() {
        var result = runner.run(List.of("sh", "-c", "exit 3"), Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(3);
    }

    @Test
    void capturesStderrSeparatelyFromStdout() {
        var result = runner.run(List.of("sh", "-c", "echo out; echo err >&2"), Duration.ofSeconds(5));

        assertThat(result.stdout()).contains("out").doesNotContain("err");
        assertThat(result.stderr()).contains("err").doesNotContain("out");
    }

    @Test
    void killsAndThrowsWhenTheProcessExceedsTheTimeout() {
        assertThatThrownBy(() -> runner.run(List.of("sh", "-c", "sleep 5"), Duration.ofMillis(300)))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void throwsWhenTheBinaryDoesNotExist() {
        assertThatThrownBy(() -> runner.run(List.of("/nonexistent/binary-xyz"), Duration.ofSeconds(5)))
                .isInstanceOf(ProviderUnavailableException.class);
    }
}
