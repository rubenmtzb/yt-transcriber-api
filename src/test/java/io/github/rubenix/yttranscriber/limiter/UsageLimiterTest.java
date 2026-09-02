package io.github.rubenix.yttranscriber.limiter;

import io.github.rubenix.yttranscriber.config.ProcessingLimitsProperties;
import io.github.rubenix.yttranscriber.exception.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsageLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-24T10:00:00Z"));

    @Test
    void allowsRequestsUpToTheConfiguredHourlyLimitThenRejects() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 2, 60, 2, 1_000_000, 1_000_000), clock);

        assertThatCode(() -> limiter.checkAndRecordRequest("session-a")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAndRecordRequest("session-a")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkAndRecordRequest("session-a"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void tracksRequestBudgetsIndependentlyPerSession() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 1, 60, 2, 1_000_000, 1_000_000), clock);

        limiter.checkAndRecordRequest("session-a");

        assertThatCode(() -> limiter.checkAndRecordRequest("session-b")).doesNotThrowAnyException();
    }

    @Test
    void resetsTheRequestBudgetAfterTheWindowElapses() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 1, 60, 2, 1_000_000, 1_000_000), clock);

        limiter.checkAndRecordRequest("session-a");
        assertThatThrownBy(() -> limiter.checkAndRecordRequest("session-a")).isInstanceOf(RateLimitedException.class);

        clock.advance(Duration.ofHours(1).plusSeconds(1));

        assertThatCode(() -> limiter.checkAndRecordRequest("session-a")).doesNotThrowAnyException();
    }

    @Test
    void allowsAudioMinutesUpToTheConfiguredHourlyLimitThenRejects() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 100, 10, 2, 1_000_000, 1_000_000), clock);

        assertThatCode(() -> limiter.checkAndRecordAudioMinutes("session-a", 300)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAndRecordAudioMinutes("session-a", 300)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkAndRecordAudioMinutes("session-a", 60))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void roundsPartialMinutesUpWhenCheckingTheAudioBudget() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 100, 1, 2, 1_000_000, 1_000_000), clock);

        assertThatThrownBy(() -> limiter.checkAndRecordAudioMinutes("session-a", 61))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void resetsAudioMinutesBudgetAfterTheWindowElapses() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 100, 5, 2, 1_000_000, 1_000_000), clock);

        limiter.checkAndRecordAudioMinutes("session-a", 300);
        assertThatThrownBy(() -> limiter.checkAndRecordAudioMinutes("session-a", 60))
                .isInstanceOf(RateLimitedException.class);

        clock.advance(Duration.ofHours(1).plusSeconds(1));

        assertThatCode(() -> limiter.checkAndRecordAudioMinutes("session-a", 300)).doesNotThrowAnyException();
    }

        @Test
    void reportsAFullBudgetForASessionThatHasNeverBeenSeen() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 3, 60, 2, 1_000_000, 1_000_000), clock);

        UsageSnapshot snapshot = limiter.remaining("unknown-session");

        assertThat(snapshot.requestsRemaining()).isEqualTo(3);
        assertThat(snapshot.audioMinutesRemaining()).isEqualTo(60);
        assertThat(snapshot.maxVideoDurationSeconds()).isEqualTo(1200);
        // Nothing recorded, so nothing is waiting to be released.
        assertThat(snapshot.requestsResetInSeconds()).isNull();
        assertThat(snapshot.audioMinutesResetInSeconds()).isNull();
    }

    @Test
    void countsDownToTheMomentTheOldestUseLeavesTheWindow() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 3, 60, 2, 1_000_000, 1_000_000), clock);
        limiter.checkAndRecordRequest("s1");
        limiter.checkAndRecordAudioMinutes("s1", 120);

        clock.advance(Duration.ofMinutes(20));
        UsageSnapshot snapshot = limiter.remaining("s1");

        assertThat(snapshot.requestsRemaining()).isEqualTo(2);
        assertThat(snapshot.audioMinutesRemaining()).isEqualTo(58);
        // Recorded 20 minutes ago, so 40 minutes of its hour are left.
        assertThat(snapshot.requestsResetInSeconds()).isEqualTo(2400);
        assertThat(snapshot.audioMinutesResetInSeconds()).isEqualTo(2400);
    }

    @Test
    void tracksTheOldestSurvivingUseAsEarlierOnesExpire() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 3, 60, 2, 1_000_000, 1_000_000), clock);
        limiter.checkAndRecordRequest("s1");
        clock.advance(Duration.ofMinutes(30));
        limiter.checkAndRecordRequest("s1");

        // The first request has just aged out; the countdown now belongs to the second.
        clock.advance(Duration.ofMinutes(31));
        UsageSnapshot snapshot = limiter.remaining("s1");

        assertThat(snapshot.requestsRemaining()).isEqualTo(2);
        assertThat(snapshot.requestsResetInSeconds()).isEqualTo(29 * 60);
    }

    @Test
    void doesNotRegisterASessionJustForAskingWhatItHasLeft() {
        // The frontend polls this on every page load; recording those would grow the map for
        // visitors who never transcribe anything.
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 1, 60, 2, 1_000_000, 1_000_000), clock);

        limiter.remaining("curious");
        limiter.remaining("curious");

        assertThatCode(() -> limiter.checkAndRecordRequest("curious")).doesNotThrowAnyException();
    }

    @Test
    void dropsSessionsWhoseWindowsHaveFullyExpired() {
        // Anonymous ids are minted per browser and mostly never come back, so without this sweep
        // the map gains an entry per distinct caller and never releases one.
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 5, 60, 2, 1_000_000, 1_000_000), clock);

        limiter.checkAndRecordRequest("session-a");
        limiter.checkAndRecordAudioMinutes("session-b", 120);
        assertThat(limiter.trackedSessionCount()).isEqualTo(2);

        // past the usage window, so both are spent, and past the sweep interval, so a pass is due
        clock.advance(Duration.ofHours(1).plusMinutes(6));
        limiter.checkAndRecordRequest("session-c");

        assertThat(limiter.trackedSessionCount()).isEqualTo(1);
    }

    @Test
    void keepsSessionsStillInsideTheirWindowWhenSweeping() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 5, 60, 2, 1_000_000, 1_000_000), clock);

        limiter.checkAndRecordRequest("session-a");

        // past the sweep interval but well inside the one-hour window: session-a is still live
        clock.advance(Duration.ofMinutes(6));
        limiter.checkAndRecordRequest("session-b");

        assertThat(limiter.trackedSessionCount()).isEqualTo(2);
        assertThatThrownBy(() -> {
            for (int i = 0; i < 5; i++) {
                limiter.checkAndRecordRequest("session-a");
            }
        }).isInstanceOf(RateLimitedException.class);
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
