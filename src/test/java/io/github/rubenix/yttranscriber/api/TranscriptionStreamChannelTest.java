package io.github.rubenix.yttranscriber.api;

import io.github.rubenix.yttranscriber.api.TranscriptionStreamChannel.StreamAborted;
import io.github.rubenix.yttranscriber.application.ProcessingStage;
import io.github.rubenix.yttranscriber.exception.ErrorCode;
import io.github.rubenix.yttranscriber.exception.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TranscriptionStreamChannelTest {

    @Mock
    private SseEmitter emitter;

    @Test
    void abortsTheRunWhenAWriteFailsBecauseTheClientLeft() throws Exception {
        doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        TranscriptionStreamChannel channel = new TranscriptionStreamChannel(emitter);

        assertThatThrownBy(() -> channel.sendStage(ProcessingStage.RESOLVING_VIDEO))
                .isInstanceOf(StreamAborted.class);
    }

    @Test
    void stopsWritingOnceTheContainerHasReportedTheDisconnect() throws Exception {
        TranscriptionStreamChannel channel = new TranscriptionStreamChannel(emitter);

        // The container signals a dropped connection through the onError callback the channel
        // registered on construction, which can land before any write of ours would have failed.
        errorCallbackOf(emitter).accept(new IOException("client reset"));

        assertThatThrownBy(() -> channel.sendStage(ProcessingStage.TRANSLATING))
                .isInstanceOf(StreamAborted.class);
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void keepsStreamingWhileTheClientIsStillThere() throws Exception {
        TranscriptionStreamChannel channel = new TranscriptionStreamChannel(emitter);

        assertThatCode(() -> channel.sendStage(ProcessingStage.RESOLVING_VIDEO)).doesNotThrowAnyException();
        assertThatCode(() -> channel.sendStage(ProcessingStage.TRANSLATING)).doesNotThrowAnyException();

        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void swallowsAFailedErrorEventBecauseThereIsNowhereLeftToReportIt() throws Exception {
        doThrow(new IOException("broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        TranscriptionStreamChannel channel = new TranscriptionStreamChannel(emitter);

        assertThatCode(() -> channel.sendError(
                ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "boom", "request-1"))).doesNotThrowAnyException();
    }

    @Test
    void keepsTheConnectionAliveWhileAStageIsRunning() {
        // A stage can run for minutes with nothing to report, and Cloudflare gives up on a proxied
        // response that goes 100 seconds without a chunk -- so silence is what turned the longest
        // runs, the ones that had nearly finished, into "lost connection".
        RecordingEmitter recording = new RecordingEmitter();
        new TranscriptionStreamChannel(recording, Duration.ofMillis(30));

        awaitUntil(() -> recording.written().size() >= 2);

        // Comments, not events. The SSE grammar requires a reader to ignore them, which is what
        // lets this keep the connection open without the frontend knowing it exists.
        assertThat(recording.written()).allSatisfy(written -> assertThat(written).startsWith(":"));
    }

    @Test
    void stopsBeatingOnceTheRunIsOver() throws Exception {
        RecordingEmitter recording = new RecordingEmitter();
        TranscriptionStreamChannel channel = new TranscriptionStreamChannel(recording, Duration.ofMillis(30));
        awaitUntil(() -> !recording.written().isEmpty());

        channel.complete();
        int writtenAtCompletion = recording.written().size();

        // Several more intervals, had anything still been beating. A heartbeat outliving its run
        // would write into a completed emitter for as long as the process stays up, one thread per
        // stream that was ever opened.
        Thread.sleep(200);
        assertThat(recording.written()).hasSize(writtenAtCompletion);
    }

    private static void awaitUntil(BooleanSupplier condition) {
        Instant deadline = Instant.now().plusSeconds(2);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not met within 2s");
            }
            Thread.onSpinWait();
        }
    }

    /**
     * A real emitter that keeps what was written to it, rather than a mock.
     *
     * <p>The heartbeat writes from its own thread while the test thread reads, and Mockito records
     * invocations in a structure that is not safe to verify while another thread is still calling
     * the mock -- the failures that produces look like flakiness rather than like the race they are.
     */
    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> written = new CopyOnWriteArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder payload = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                payload.append(part.getData());
            }
            written.add(payload.toString());
        }

        List<String> written() {
            return written;
        }
    }

    @SuppressWarnings("unchecked")
    private static Consumer<Throwable> errorCallbackOf(SseEmitter emitter) {
        ArgumentCaptor<Consumer<Throwable>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onError(captor.capture());
        return captor.getValue();
    }
}
