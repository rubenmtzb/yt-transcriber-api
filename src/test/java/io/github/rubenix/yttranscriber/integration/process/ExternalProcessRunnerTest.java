package io.github.rubenix.yttranscriber.integration.process;

import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import io.github.rubenix.yttranscriber.application.ProcessingBudget;
import io.github.rubenix.yttranscriber.exception.ProcessingTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalProcessRunnerTest {

    private final ExternalProcessRunner runner = new ExternalProcessRunner();

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

    @Test
    void globalBudgetCapsALongerProcessTimeout() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> ProcessingBudget.run(Duration.ofMillis(200),
                () -> runner.run(List.of("sh", "-c", "exec sleep 5"), Duration.ofSeconds(10))))
                .isInstanceOf(ProcessingTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void expiredBudgetPreventsStartingAnotherProcess() {
        assertThatThrownBy(() -> ProcessingBudget.run(Duration.ofNanos(1),
                () -> runner.run(List.of("/nonexistent/binary-xyz"), Duration.ofSeconds(10))))
                .isInstanceOf(ProcessingTimeoutException.class);
    }
}
