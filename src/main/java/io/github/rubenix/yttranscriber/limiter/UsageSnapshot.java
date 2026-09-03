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

    /**
     * The stricter reading of the two buckets a caller is charged against, so the panel never
     * promises an allowance the other bucket would refuse.
     *
     * <p>Merged per budget rather than per field: the three request fields have to agree with each
     * other (a remaining, its maximum, and when the next slot frees up describe one window), and
     * mixing halves of two windows would produce a countdown that belongs to neither. Requests and
     * audio minutes are independent, so they are chosen separately.
     */
    public static UsageSnapshot tighterOf(UsageSnapshot first, UsageSnapshot second) {
        UsageSnapshot byRequests = first.requestsRemaining() <= second.requestsRemaining() ? first : second;
        UsageSnapshot byAudio = first.audioMinutesRemaining() <= second.audioMinutesRemaining() ? first : second;
        return new UsageSnapshot(
                byRequests.requestsRemaining(),
                byRequests.maxRequestsPerHour(),
                byRequests.requestsResetInSeconds(),
                byAudio.audioMinutesRemaining(),
                byAudio.maxAudioMinutesPerHour(),
                byAudio.audioMinutesResetInSeconds(),
                first.maxVideoDurationSeconds());
    }
}
