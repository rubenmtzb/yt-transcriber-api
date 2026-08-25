package io.github.rubenix.yttranscriber.limiter;

import io.github.rubenix.yttranscriber.config.ProcessingLimitsProperties;
import io.github.rubenix.yttranscriber.exception.RateLimitedException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Tracks per-session usage against the hourly request and audio-minute budgets configured in
 * {@link ProcessingLimitsProperties}. Sessions are anonymous header-carried identities (see
 * {@link SessionIdFilter}), not IP addresses — a deliberate V1 scope decision, since this
 * runs as a single local instance without a reverse proxy in front of it yet.
 *
 * <p>Usage is kept in memory per session as a rolling one-hour window; entries older than an hour
 * are evicted lazily on each check, and sessions whose windows have emptied out entirely are
 * dropped from the map by a throttled sweep — without that, the map would gain an entry per
 * distinct session id and never give one back, which for anonymous ids (a fresh one per browser
 * that never sends one back) grows without bound for as long as the process lives.
 *
 * <p>Every read-modify-write runs inside {@link ConcurrentHashMap#compute} rather than under a
 * separate per-session lock, so the sweep — which removes through
 * {@link ConcurrentHashMap#computeIfPresent} — contends on the very same per-key lock. A session
 * therefore cannot be swept away in the window between another thread reading it and recording
 * into it, which would silently hand that caller a fresh budget.
 */
@Component
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
