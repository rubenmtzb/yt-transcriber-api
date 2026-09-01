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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tracks per-session usage against the hourly request and audio-minute budgets configured in
 * {@link ProcessingLimitsProperties}. Sessions are anonymous cookie identities (see
 * {@link SessionIdFilter}), not IP addresses — a deliberate V1 scope decision, since this
 * runs as a single local instance without a reverse proxy in front of it yet.
 *
 * <p>Usage is kept in-memory per session as a rolling one-hour window; entries older than an
 * hour are evicted lazily on each check, and a session whose window has emptied is dropped from
 * the map entirely, so the map tracks only sessions with live usage rather than every visitor the
 * process has ever seen.
 */
@Component
public class UsageLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);
    // Nothing can expire in under an hour, so sweeping on every request only re-walks a map that
    // cannot have changed. Measured: a full sweep of 100k live sessions costs ~3ms, which paid on
    // every request would dominate the limiter; paid once a minute it disappears.
    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(1);

    private final ProcessingLimitsProperties limits;
    private final Clock clock;
    private final ConcurrentHashMap<String, SessionUsage> usageBySession = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastPurge = new AtomicReference<>(Instant.EPOCH);

    public UsageLimiter(ProcessingLimitsProperties limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    public void checkAndRecordRequest(String sessionId) {
        purgeIdleSessions();
        SessionUsage usage = usageBySession.computeIfAbsent(sessionId, id -> new SessionUsage());
        usage.lock.lock();
        try {
            Instant now = clock.instant();
            evict(usage.requestTimestamps, java.util.function.Function.identity(), now);
            if (usage.requestTimestamps.size() >= limits.maxRequestsPerHour()) {
                throw new RateLimitedException(
                        "You've reached the limit of %d requests per hour.".formatted(limits.maxRequestsPerHour()));
            }
            usage.requestTimestamps.addLast(now);
        } finally {
            usage.lock.unlock();
        }
    }

    public void checkAndRecordAudioMinutes(String sessionId, long durationSeconds) {
        long minutes = Math.max(1, Math.ceilDiv(durationSeconds, 60));
        SessionUsage usage = usageBySession.computeIfAbsent(sessionId, id -> new SessionUsage());
        usage.lock.lock();
        try {
            Instant now = clock.instant();
            evict(usage.audioUsage, AudioUsageEntry::recordedAt, now);
            long consumed = usage.audioUsage.stream().mapToLong(AudioUsageEntry::minutes).sum();
            if (consumed + minutes > limits.maxAudioMinutesPerHour()) {
                throw new RateLimitedException(
                        "You've reached the limit of %d audio minutes per hour.".formatted(limits.maxAudioMinutesPerHour()));
            }
            usage.audioUsage.addLast(new AudioUsageEntry(now, minutes));
        } finally {
            usage.lock.unlock();
        }
    }

    /**
     * Read-only view of what's left in the session's window. Deliberately does not create an entry
     * for an unknown session: this is polled by the frontend on every page load, and recording
     * those would grow the map for visitors who never actually transcribe anything.
     */
    public UsageSnapshot remaining(String sessionId) {
        SessionUsage usage = usageBySession.get(sessionId);
        if (usage == null) {
            return new UsageSnapshot(
                    limits.maxRequestsPerHour(), limits.maxRequestsPerHour(), null,
                    limits.maxAudioMinutesPerHour(), limits.maxAudioMinutesPerHour(), null,
                    limits.maxVideoDurationSeconds());
        }

        usage.lock.lock();
        try {
            Instant now = clock.instant();
            evict(usage.requestTimestamps, java.util.function.Function.identity(), now);
            evict(usage.audioUsage, AudioUsageEntry::recordedAt, now);
            long consumedMinutes = usage.audioUsage.stream().mapToLong(AudioUsageEntry::minutes).sum();
            return new UsageSnapshot(
                    Math.max(0, limits.maxRequestsPerHour() - usage.requestTimestamps.size()),
                    limits.maxRequestsPerHour(),
                    secondsUntilOldestExpires(usage.requestTimestamps.peekFirst(), now),
                    Math.max(0, limits.maxAudioMinutesPerHour() - consumedMinutes),
                    limits.maxAudioMinutesPerHour(),
                    secondsUntilOldestExpires(
                            usage.audioUsage.isEmpty() ? null : usage.audioUsage.peekFirst().recordedAt(), now),
                    limits.maxVideoDurationSeconds());
        } finally {
            usage.lock.unlock();
        }
    }

    /**
     * When the oldest recorded use leaves the window, which is the moment that counter goes back
     * up. Callers get null when nothing is recorded, since then nothing is waiting to be released.
     * Rounded up, so a countdown never claims "0" while the slot is still held.
     */
    private Long secondsUntilOldestExpires(Instant oldest, Instant now) {
        if (oldest == null) {
            return null;
        }
        Duration remaining = WINDOW.minus(Duration.between(oldest, now));
        return Math.max(0, Math.ceilDiv(remaining.toMillis(), 1000));
    }

    /** Which sessions are still being tracked. Exposed for tests to assert the map stays bounded. */
    java.util.Set<String> trackedSessions() {
        return java.util.Set.copyOf(usageBySession.keySet());
    }

    /**
     * Forgets sessions whose whole window has aged out.
     *
     * Piggy-backed on recording a request rather than run on a timer: a session only becomes
     * forgettable through the passage of time, and a process with no traffic has nothing to forget.
     * A session that is dropped and then returns simply starts a fresh window, which is what an
     * empty window meant anyway.
     *
     * Throttled to one sweep a minute, and claimed with a compare-and-set so concurrent requests
     * don't all walk the map at once.
     */
    private void purgeIdleSessions() {
        Instant now = clock.instant();
        Instant last = lastPurge.get();
        if (Duration.between(last, now).compareTo(PURGE_INTERVAL) < 0 || !lastPurge.compareAndSet(last, now)) {
            return;
        }

        usageBySession.values().removeIf(usage -> {
            if (!usage.lock.tryLock()) {
                // Held means in use, which is the opposite of idle.
                return false;
            }
            try {
                evict(usage.requestTimestamps, java.util.function.Function.identity(), now);
                evict(usage.audioUsage, AudioUsageEntry::recordedAt, now);
                return usage.requestTimestamps.isEmpty() && usage.audioUsage.isEmpty();
            } finally {
                usage.lock.unlock();
            }
        });
    }

    private <T> void evict(Deque<T> entries, java.util.function.Function<T, Instant> timestampOf, Instant now) {
        while (!entries.isEmpty() && Duration.between(timestampOf.apply(entries.peekFirst()), now).compareTo(WINDOW) >= 0) {
            entries.pollFirst();
        }
    }

    private record AudioUsageEntry(Instant recordedAt, long minutes) {
    }

    private static final class SessionUsage {
        private final Deque<Instant> requestTimestamps = new ArrayDeque<>();
        private final Deque<AudioUsageEntry> audioUsage = new ArrayDeque<>();
        private final ReentrantLock lock = new ReentrantLock();
    }
}
