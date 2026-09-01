package io.github.rubenix.yttranscriber.limiter;

/**
 * What a session has left of its hourly budget, so the frontend can show the cost of a request
 * before it's spent rather than only reporting it once a request has already been refused.
 *
 * <p>The budgets are rolling windows, not a counter that empties on the hour: each recorded use
 * frees itself exactly an hour after it happened. The {@code ...ResetInSeconds} fields are how
 * long until the oldest recorded use falls out of its window -- the moment that counter next goes
 * up -- and are null when nothing is recorded and so nothing is pending.
 */
public record UsageSnapshot(
        int requestsRemaining,
        int maxRequestsPerHour,
        Long requestsResetInSeconds,
        long audioMinutesRemaining,
        long maxAudioMinutesPerHour,
        Long audioMinutesResetInSeconds,
        long maxVideoDurationSeconds) {
}
