package io.github.rubenix.yttranscriber.api;

import io.github.rubenix.yttranscriber.api.dto.TranscriptionResponseDto;
import io.github.rubenix.yttranscriber.application.ProcessingStage;
import io.github.rubenix.yttranscriber.exception.ErrorResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the wire side of one streaming transcription: writes the protocol's events, keeps the
 * connection alive across the long silent stages, and tracks whether the client is still listening.
 *
 * <p>Once it isn't, {@link #sendStage} throws {@link StreamAborted} instead of writing. That
 * unwinds the pipeline, which is what releases the capacity permit the run is holding -- carrying
 * on would keep one of very few processing slots busy building a result nobody will ever read.
 * Cancellation lands at the next stage boundary rather than instantly: there is no cheap way to
 * kill a blocking subprocess mid-call, so an in-flight yt-dlp or whisper-cli still finishes the
 * stage it is on before the run gives up.
 */
class TranscriptionStreamChannel {

    /**
     * How often a comment is written while a stage is running.
     *
     * <p>Stages are not evenly spaced: nothing at all goes down the wire between one stage event and
     * the next, and the gaps are long. Resolving a video is allowed 120 seconds, and the
     * Speech-to-Text path runs whisper-cli for minutes on a long video. Cloudflare, which proxies
     * this deployment, gives up on a response that goes 100 seconds without a chunk -- so the
     * quietest stretches were the ones that had already done nearly all the work, and they surfaced
     * to the reader as "lost connection" with nothing to show for the wait.
     *
     * <p>A comment is the right shape for this: the SSE grammar defines it as a line to be ignored,
     * so {@code EventSource} never surfaces it and the frontend needs to know nothing about it. It
     * only has to be bytes, arriving often enough.
     */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(20);

    private final SseEmitter emitter;
    private final AtomicBoolean clientGone = new AtomicBoolean(false);
    /**
     * Serialises writes. The heartbeat runs on its own thread, so without this it and the pipeline
     * thread could be inside {@link SseEmitter#send} at the same time -- which interleaves two
     * events on the wire and leaves the reader parsing a message that never existed.
     */
    private final ReentrantLock writeLock = new ReentrantLock();
    private final CountDownLatch finished = new CountDownLatch(1);

    TranscriptionStreamChannel(SseEmitter emitter) {
        this(emitter, HEARTBEAT_INTERVAL);
    }

    TranscriptionStreamChannel(SseEmitter emitter, Duration heartbeatInterval) {
        this.emitter = emitter;
        // The container reports a dropped connection asynchronously, so this can flip before our
        // next write would have failed on its own -- which is exactly what lets a long stage be
        // the last one we run rather than the last one we notice.
        emitter.onError(throwable -> clientGone.set(true));
        emitter.onTimeout(() -> clientGone.set(true));
        Thread.ofVirtual().start(() -> beatUntilFinished(heartbeatInterval));
    }

    void sendSession(String sessionId) throws IOException {
        send(SseEmitter.event().name("session").data(sessionId));
    }

    void sendStage(ProcessingStage stage) {
        if (clientGone.get()) {
            throw new StreamAborted();
        }
        try {
            // .data(String) is written to the wire as-is; .data(Object) instead runs it through
            // the JSON message converter, which would wrap a bare enum in quotes ("RESOLVING_VIDEO"
            // instead of RESOLVING_VIDEO) -- confirmed with a raw curl trace of the stream, since
            // the frontend comparing that raw event data against ProcessingStage string literals
            // would then silently never match past the very first (hardcoded, pre-stream) stage.
            send(SseEmitter.event().name("stage").data(stage.name()));
        } catch (IOException | IllegalStateException e) {
            clientGone.set(true);
            throw new StreamAborted();
        }
    }

    void sendResult(TranscriptionResponseDto result) throws IOException {
        send(SseEmitter.event().name("result").data(result));
    }

    void sendError(ErrorResponse error) {
        try {
            send(SseEmitter.event().name("error").data(error));
        } catch (IOException | IllegalStateException ignored) {
            // client already gone; there is nowhere left to report this
        }
    }

    void complete() {
        // Stops the heartbeat before completing, so it cannot write into an emitter that is on its
        // way out. A beat already in flight still holds the lock, and completion waits behind it.
        finished.countDown();
        writeLock.lock();
        try {
            emitter.complete();
        } finally {
            writeLock.unlock();
        }
    }

    private void send(SseEmitter.SseEventBuilder event) throws IOException {
        writeLock.lock();
        try {
            emitter.send(event);
        } finally {
            writeLock.unlock();
        }
    }

    private void beatUntilFinished(Duration interval) {
        try {
            // Waits on the latch rather than sleeping, so a run that finishes in two seconds takes
            // the heartbeat thread down with it instead of leaving it parked for a full interval.
            while (!finished.await(interval.toMillis(), TimeUnit.MILLISECONDS)) {
                if (clientGone.get() || !beat()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return false once the connection is gone and there is no point beating again */
    private boolean beat() {
        try {
            send(SseEmitter.event().comment("keep-alive"));
            return true;
        } catch (IOException | IllegalStateException e) {
            // Either the client hung up or the run completed between the latch check and this write.
            // Both mean the same thing here: stop.
            clientGone.set(true);
            return false;
        }
    }

    /**
     * Control flow, not a fault: raised to unwind a run whose client has gone away. Carries no
     * stack trace because nothing ever inspects one.
     */
    static final class StreamAborted extends RuntimeException {

        StreamAborted() {
            super(null, null, false, false);
        }
    }
}
