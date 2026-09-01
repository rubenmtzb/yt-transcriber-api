package io.github.rubenix.yttranscriber.api;

import io.github.rubenix.yttranscriber.api.dto.TranscriptionResponseDto;
import io.github.rubenix.yttranscriber.application.ProcessingStage;
import io.github.rubenix.yttranscriber.exception.ErrorResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the wire side of one streaming transcription: writes the protocol's events and tracks
 * whether the client is still listening.
 *
 * <p>Once it isn't, {@link #sendStage} throws {@link StreamAborted} instead of writing. That
 * unwinds the pipeline, which is what releases the capacity permit the run is holding -- carrying
 * on would keep one of very few processing slots busy building a result nobody will ever read.
 * Cancellation lands at the next stage boundary rather than instantly: there is no cheap way to
 * kill a blocking subprocess mid-call, so an in-flight yt-dlp or whisper-cli still finishes the
 * stage it is on before the run gives up.
 */
class TranscriptionStreamChannel {

    private final SseEmitter emitter;
    private final AtomicBoolean clientGone = new AtomicBoolean(false);

    TranscriptionStreamChannel(SseEmitter emitter) {
        this.emitter = emitter;
        // The container reports a dropped connection asynchronously, so this can flip before our
        // next write would have failed on its own -- which is exactly what lets a long stage be
        // the last one we run rather than the last one we notice.
        emitter.onError(throwable -> clientGone.set(true));
        emitter.onTimeout(() -> clientGone.set(true));
    }

    void sendSession(String sessionId) throws IOException {
        emitter.send(SseEmitter.event().name("session").data(sessionId));
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
            emitter.send(SseEmitter.event().name("stage").data(stage.name()));
        } catch (IOException | IllegalStateException e) {
            clientGone.set(true);
            throw new StreamAborted();
        }
    }

    void sendResult(TranscriptionResponseDto result) throws IOException {
        emitter.send(SseEmitter.event().name("result").data(result));
    }

    void sendError(ErrorResponse error) {
        try {
            emitter.send(SseEmitter.event().name("error").data(error));
        } catch (IOException | IllegalStateException ignored) {
            // client already gone; there is nowhere left to report this
        }
    }

    void complete() {
        emitter.complete();
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
