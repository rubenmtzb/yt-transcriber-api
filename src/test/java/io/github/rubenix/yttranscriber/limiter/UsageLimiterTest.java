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

        @Test
    void reportsAFullBudgetForASessionThatHasNeverBeenSeen() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 3, 60, 2), clock);

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
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 3, 60, 2), clock);
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
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 3, 60, 2), clock);
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
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 1, 60, 2), clock);

        limiter.remaining("curious");
        limiter.remaining("curious");

        assertThatCode(() -> limiter.checkAndRecordRequest("curious")).doesNotThrowAnyException();
    }

        @Test
    void forgetsSessionsWhoseWholeWindowHasAgedOut() {
        // The map used to keep every session the process had ever seen, which grows without bound
        // on a long-lived instance.
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 3, 60, 2), clock);
        limiter.checkAndRecordRequest("old-visitor");
        limiter.checkAndRecordAudioMinutes("old-visitor", 60);

        clock.advance(Duration.ofHours(1).plusMinutes(1));
        limiter.checkAndRecordRequest("someone-else");

        assertThat(limiter.trackedSessions()).containsExactly("someone-else");
    }

    @Test
    void keepsASessionThatStillHasUsageInsideItsWindow() {
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 3, 60, 2), clock);
        limiter.checkAndRecordRequest("active");

        clock.advance(Duration.ofMinutes(30));
        limiter.checkAndRecordRequest("someone-else");

        assertThat(limiter.trackedSessions()).contains("active", "someone-else");
    }

        @Test
    void doesNotSweepTheWholeMapOnEveryRequest() {
        // A sweep walks and locks every live session, which at scale costs more than the request
        // it is attached to. Nothing can expire in under an hour, so it is throttled -- and a
        // session that ages out between sweeps simply survives until the next one.
        UsageLimiter limiter = new UsageLimiter(new ProcessingLimitsProperties(1200, 5, 60, 2), clock);
        limiter.checkAndRecordRequest("ages-out");

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        // Far enough for the entry to have expired, but the sweep that would notice has just run.
        limiter.checkAndRecordRequest("first");
        clock.advance(Duration.ofSeconds(5));
        limiter.checkAndRecordRequest("second");

        assertThat(limiter.trackedSessions()).contains("first", "second");
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
