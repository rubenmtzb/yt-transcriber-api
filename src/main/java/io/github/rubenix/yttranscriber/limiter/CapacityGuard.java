package io.github.rubenix.yttranscriber.limiter;

import io.github.rubenix.yttranscriber.config.ProcessingLimitsProperties;
import io.github.rubenix.yttranscriber.exception.RateLimitedException;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * Caps the number of transcriptions the backend processes at once. Each request spawns external
 * subprocesses (yt-dlp, and on the Speech-to-Text path CPU-bound whisper-cli inference) and blocks
 * a thread for the duration of the pipeline; without this, a burst of concurrent requests could
 * overwhelm a single local instance.
 *
 * <p>Rejects rather than queues: the caller is a browser waiting on a synchronous response, so
 * making it wait behind an unbounded queue would just turn a fast, actionable "busy, retry" into a
 * request that eventually times out with nothing to show for it.
 */
@Component
public class CapacityGuard {

    private final Semaphore permits;

    public CapacityGuard(ProcessingLimitsProperties limits) {
        this.permits = new Semaphore(limits.maxConcurrentTranscriptions());
    }

    public <T> T runWithinCapacity(Supplier<T> work) {
        if (!permits.tryAcquire()) {
            throw new RateLimitedException("The server is currently busy processing other requests. Please try again shortly.");
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }
}
