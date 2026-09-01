package io.github.rubenix.yttranscriber.limiter;

import io.github.rubenix.yttranscriber.config.ProcessingLimitsProperties;
import io.github.rubenix.yttranscriber.exception.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapacityGuardTest {

    @Test
    void rejectsWorkOnceTheConcurrencyLimitIsReachedThenAllowsItAfterReleasing() throws Exception {
        CapacityGuard guard = new CapacityGuard(new ProcessingLimitsProperties(1200, 100, 6000, 1));
        CountDownLatch firstCallStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> firstCall = executor.submit(() -> guard.runWithinCapacity(() -> {
                firstCallStarted.countDown();
                awaitUninterruptibly(releaseFirstCall);
                return "done";
            }));
            assertThat(firstCallStarted.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> guard.runWithinCapacity(() -> "should not run"))
                    .isInstanceOf(RateLimitedException.class);

            releaseFirstCall.countDown();
            assertThat(firstCall.get(2, TimeUnit.SECONDS)).isEqualTo("done");

            assertThat(guard.runWithinCapacity(() -> "after release")).isEqualTo("after release");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void releasesThePermitEvenWhenTheWorkThrows() {
        CapacityGuard guard = new CapacityGuard(new ProcessingLimitsProperties(1200, 100, 6000, 1));

        assertThatThrownBy(() -> guard.runWithinCapacity(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(guard.runWithinCapacity(() -> "still works")).isEqualTo("still works");
    }

    private void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
