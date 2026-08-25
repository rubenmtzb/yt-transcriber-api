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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.function.Consumer;

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

    @SuppressWarnings("unchecked")
    private static Consumer<Throwable> errorCallbackOf(SseEmitter emitter) {
        ArgumentCaptor<Consumer<Throwable>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onError(captor.capture());
        return captor.getValue();
    }
}
