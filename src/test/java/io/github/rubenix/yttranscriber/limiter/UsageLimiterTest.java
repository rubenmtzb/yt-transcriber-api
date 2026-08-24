package io.github.rubenix.yttranscriber.limiter;

import io.github.rubenix.yttranscriber.config.ProcessingLimitsProperties;
import io.github.rubenix.yttranscriber.exception.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-24T10:00:00Z"));

    @Test
    void allowsRequestsUpToTheConfiguredHourlyLimitThenRejects() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 2, 60, 2), clock);

        assertThatCode(() -> limiter.checkAndRecordRequest("session-a")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAndRecordRequest("session-a")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkAndRecordRequest("session-a"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void tracksRequestBudgetsIndependentlyPerSession() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 1, 60, 2), clock);

        limiter.checkAndRecordRequest("session-a");

        assertThatCode(() -> limiter.checkAndRecordRequest("session-b")).doesNotThrowAnyException();
    }

    @Test
    void resetsTheRequestBudgetAfterTheWindowElapses() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 1, 60, 2), clock);

        limiter.checkAndRecordRequest("session-a");
        assertThatThrownBy(() -> limiter.checkAndRecordRequest("session-a")).isInstanceOf(RateLimitedException.class);

        clock.advance(Duration.ofHours(1).plusSeconds(1));

        assertThatCode(() -> limiter.checkAndRecordRequest("session-a")).doesNotThrowAnyException();
    }

    @Test
    void allowsAudioMinutesUpToTheConfiguredHourlyLimitThenRejects() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 100, 10, 2), clock);

        assertThatCode(() -> limiter.checkAndRecordAudioMinutes("session-a", 300)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAndRecordAudioMinutes("session-a", 300)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkAndRecordAudioMinutes("session-a", 60))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void roundsPartialMinutesUpWhenCheckingTheAudioBudget() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 100, 1, 2), clock);

        assertThatThrownBy(() -> limiter.checkAndRecordAudioMinutes("session-a", 61))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void resetsAudioMinutesBudgetAfterTheWindowElapses() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 100, 5, 2), clock);

        limiter.checkAndRecordAudioMinutes("session-a", 300);
        assertThatThrownBy(() -> limiter.checkAndRecordAudioMinutes("session-a", 60))
                .isInstanceOf(RateLimitedException.class);

        clock.advance(Duration.ofHours(1).plusSeconds(1));

        assertThatCode(() -> limiter.checkAndRecordAudioMinutes("session-a", 300)).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
