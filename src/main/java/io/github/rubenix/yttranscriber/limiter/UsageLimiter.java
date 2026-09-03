package io.github.rubenix.yttranscriber.limiter;

import io.github.rubenix.yttranscriber.config.ProcessingLimitsProperties;
import io.github.rubenix.yttranscriber.exception.RateLimitedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Tracks usage for one identity against the hourly request and audio-minute budgets configured in
 * {@link ProcessingLimitsProperties}. What the identity <em>is</em> is the caller's business: there
 * are two instances of this (see {@code UsageLimiterConfig}), one keyed on the anonymous session id
 * from {@link SessionIdFilter} and one keyed on the client address from
 * {@link ClientIpFilter}. Only the second bounds anything -- a session id is chosen by the caller,
 * so a fresh one buys a fresh budget -- but both are charged, because the session budget is what
 * gives a person an honest read on their own remaining allowance.
 *
 * <p>Usage is kept in memory per identity as a rolling one-hour window; entries older than an hour
 * are evicted lazily on each check, and identities whose windows have emptied out entirely are
 * dropped from the map by a throttled sweep — without that, the map would gain an entry per
 * distinct identity and never give one back, which for anonymous ids (a fresh one per browser
 * that never sends one back) grows without bound for as long as the process lives.
 *
 * <p>Every read-modify-write runs inside {@link ConcurrentHashMap#compute} rather than under a
 * separate per-session lock, so the sweep — which removes through
 * {@link ConcurrentHashMap#computeIfPresent} — contends on the very same per-key lock. A session
 * therefore cannot be swept away in the window between another thread reading it and recording
 * into it, which would silently hand that caller a fresh budget.
 */
public class UsageLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Duration SWEEP_INTERVAL = Duration.ofMinutes(5);

    private final ProcessingLimitsProperties limits;
    private final Clock clock;
    private final ConcurrentHashMap<String, SessionUsage> usageBySession = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> nextSweepAt;

    public UsageLimiter(ProcessingLimitsProperties limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
        this.nextSweepAt = new AtomicReference<>(clock.instant().plus(SWEEP_INTERVAL));
    }

    public void checkAndRecordRequest(String sessionId) {
        Instant now = clock.instant();
        purgeIdleSessions(now);

        usageBySession.compute(sessionId, (id, existing) -> {
            SessionUsage usage = existing != null ? existing : new SessionUsage();
            evict(usage.requestTimestamps, Function.identity(), now);
            if (usage.requestTimestamps.size() >= limits.maxRequestsPerHour()) {
                throw new RateLimitedException(
                        "You've reached the limit of %d requests per hour.".formatted(limits.maxRequestsPerHour()));
            }
            usage.requestTimestamps.addLast(now);
            return usage;
        });
    }

    public void checkAndRecordAudioMinutes(String sessionId, long durationSeconds) {
        long minutes = Math.max(1, Math.ceilDiv(durationSeconds, 60));
        Instant now = clock.instant();
        purgeIdleSessions(now);

        usageBySession.compute(sessionId, (id, existing) -> {
            SessionUsage usage = existing != null ? existing : new SessionUsage();
            evict(usage.audioUsage, AudioUsageEntry::recordedAt, now);
            long consumed = usage.audioUsage.stream().mapToLong(AudioUsageEntry::minutes).sum();
            if (consumed + minutes > limits.maxAudioMinutesPerHour()) {
                throw new RateLimitedException(
                        "You've reached the limit of %d audio minutes per hour.".formatted(limits.maxAudioMinutesPerHour()));
            }
            usage.audioUsage.addLast(new AudioUsageEntry(now, minutes));
            return usage;
        });
    }

    /**
     * Read-only view of what the session has left. Deliberately never creates an entry: the
     * frontend polls this on every page load, and recording those would grow the map with visitors
     * who never actually transcribe anything -- exactly what the sweep below exists to avoid.
     *
     * <p>The eviction still has to run inside {@code computeIfPresent}: the deques are plain
     * {@link ArrayDeque}s, so reading one while another thread records into it is not safe.
     */
    public UsageSnapshot remaining(String sessionId) {
        Instant now = clock.instant();
        AtomicReference<UsageSnapshot> snapshot = new AtomicReference<>(fullBudget());

        usageBySession.computeIfPresent(sessionId, (id, usage) -> {
            evict(usage.requestTimestamps, Function.identity(), now);
            evict(usage.audioUsage, AudioUsageEntry::recordedAt, now);
            long consumedMinutes = usage.audioUsage.stream().mapToLong(AudioUsageEntry::minutes).sum();
            snapshot.set(new UsageSnapshot(
                    Math.max(0, limits.maxRequestsPerHour() - usage.requestTimestamps.size()),
                    limits.maxRequestsPerHour(),
                    secondsUntilOldestExpires(usage.requestTimestamps.peekFirst(), now),
                    Math.max(0, limits.maxAudioMinutesPerHour() - consumedMinutes),
                    limits.maxAudioMinutesPerHour(),
                    secondsUntilOldestExpires(
                            usage.audioUsage.isEmpty() ? null : usage.audioUsage.peekFirst().recordedAt(), now),
                    limits.maxVideoDurationSeconds()));
            return usage;
        });

        return snapshot.get();
    }

    private UsageSnapshot fullBudget() {
        return new UsageSnapshot(
                limits.maxRequestsPerHour(), limits.maxRequestsPerHour(), null,
                limits.maxAudioMinutesPerHour(), limits.maxAudioMinutesPerHour(), null,
                limits.maxVideoDurationSeconds());
    }

    /**
     * When the oldest recorded use leaves the window, which is the moment that counter goes back
     * up. Null when nothing is recorded, since then nothing is waiting to be released. Rounded up,
     * so a countdown never claims "0" while the slot is still held.
     */
    private Long secondsUntilOldestExpires(Instant oldest, Instant now) {
        if (oldest == null) {
            return null;
        }
        Duration remaining = WINDOW.minus(Duration.between(oldest, now));
        return Math.max(0, Math.ceilDiv(remaining.toMillis(), 1000));
    }

    /**
     * Drops sessions with nothing left inside the window. Throttled to one pass per
     * {@link #SWEEP_INTERVAL} so the cost stays negligible next to the work a request actually
     * does; the CAS makes exactly one caller run each due pass while the rest carry straight on.
     */
    private void purgeIdleSessions(Instant now) {
        Instant due = nextSweepAt.get();
        if (now.isBefore(due) || !nextSweepAt.compareAndSet(due, now.plus(SWEEP_INTERVAL))) {
            return;
        }
        for (String sessionId : usageBySession.keySet()) {
            usageBySession.computeIfPresent(sessionId, (id, usage) -> isIdle(usage, now) ? null : usage);
        }
    }

    private boolean isIdle(SessionUsage usage, Instant now) {
        evict(usage.requestTimestamps, Function.identity(), now);
        evict(usage.audioUsage, AudioUsageEntry::recordedAt, now);
        return usage.requestTimestamps.isEmpty() && usage.audioUsage.isEmpty();
    }

    private <T> void evict(Deque<T> entries, Function<T, Instant> timestampOf, Instant now) {
        while (!entries.isEmpty() && Duration.between(timestampOf.apply(entries.peekFirst()), now).compareTo(WINDOW) >= 0) {
            entries.pollFirst();
        }
    }

    /** How many sessions are currently held in memory — lets the eviction sweep be asserted on. */
    int trackedSessionCount() {
        return usageBySession.size();
    }

    private record AudioUsageEntry(Instant recordedAt, long minutes) {
    }

    private static final class SessionUsage {
        private final Deque<Instant> requestTimestamps = new ArrayDeque<>();
        private final Deque<AudioUsageEntry> audioUsage = new ArrayDeque<>();
    }
}
