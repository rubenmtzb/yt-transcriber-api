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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tracks per-session usage against the hourly request and audio-minute budgets configured in
 * {@link ProcessingLimitsProperties}. Sessions are anonymous cookie identities (see
 * {@link SessionIdFilter}), not IP addresses — a deliberate V1 scope decision, since this
 * runs as a single local instance without a reverse proxy in front of it yet.
 *
 * <p>Usage is kept in-memory per session as a rolling one-hour window; entries older than an
 * hour are evicted lazily on each check. This is not bounded long-term (sessions are never
 * purged from the map once created), which is acceptable for a manually-restarted local dev
 * server but would need addressing before a long-lived production deployment.
 */
@Component
public class UsageLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final ProcessingLimitsProperties limits;
    private final Clock clock;
    private final ConcurrentHashMap<String, SessionUsage> usageBySession = new ConcurrentHashMap<>();

    public UsageLimiter(ProcessingLimitsProperties limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    public void checkAndRecordRequest(String sessionId) {
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
