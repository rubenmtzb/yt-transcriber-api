package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.exception.ProcessingTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessingBudgetTest {

    @Test
    void consumesOneBudgetAcrossCallsAndDoesNotExtendShorterTimeouts() {
        AtomicLong time = new AtomicLong();
        new ProcessingBudget(Duration.ofSeconds(10), time::get).run(() -> {
            assertThat(ProcessingBudget.cap(Duration.ofSeconds(2))).isEqualTo(Duration.ofSeconds(2));
            time.set(Duration.ofSeconds(7).toNanos());
            assertThat(ProcessingBudget.cap(Duration.ofSeconds(5))).isEqualTo(Duration.ofSeconds(3));
            return null;
        });
    }

    @Test
    void refusesExpiredWorkAndAlwaysRemovesTheScope() {
        AtomicLong time = new AtomicLong();
        assertThatThrownBy(() -> new ProcessingBudget(Duration.ofSeconds(1), time::get).run(() -> {
            time.set(Duration.ofSeconds(1).toNanos());
            ProcessingBudget.check();
            return null;
        })).isInstanceOf(ProcessingTimeoutException.class);
        assertThat(ProcessingBudget.cap(Duration.ofSeconds(20))).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void neverReturnsAResultThatFinishedAfterTheDeadline() {
        AtomicLong time = new AtomicLong();
        assertThatThrownBy(() -> new ProcessingBudget(Duration.ofSeconds(1), time::get).run(() -> {
            time.set(Duration.ofSeconds(2).toNanos());
            return "too late";
        })).isInstanceOf(ProcessingTimeoutException.class);
    }

    @Test
    void nestingCannotResetTheDeadline() {
        AtomicLong time = new AtomicLong();
        new ProcessingBudget(Duration.ofSeconds(10), time::get).run(() -> {
            time.set(Duration.ofSeconds(9).toNanos());
            return ProcessingBudget.run(Duration.ofMinutes(20), () -> {
                assertThat(ProcessingBudget.cap(Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(1));
                return null;
            });
        });
    }

    @Test
    void anotherRequestDoesNotInheritTheBudget() throws Exception {
        new ProcessingBudget(Duration.ofSeconds(1), () -> 0L).run(() -> {
            var observed = new java.util.concurrent.CompletableFuture<Duration>();
            Thread.ofVirtual().start(() -> observed.complete(ProcessingBudget.cap(Duration.ofSeconds(20))));
            assertThat(observed.join()).isEqualTo(Duration.ofSeconds(20));
            return null;
        });
    }
}
