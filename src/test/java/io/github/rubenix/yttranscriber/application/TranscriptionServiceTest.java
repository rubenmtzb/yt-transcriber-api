package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.config.ProcessingLimitsProperties;
import io.github.rubenix.yttranscriber.domain.source.SourceResolution;
import io.github.rubenix.yttranscriber.domain.source.VideoMetadata;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSource;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionOutcome;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionProvider;
import io.github.rubenix.yttranscriber.domain.translation.TranslatedSegment;
import io.github.rubenix.yttranscriber.exception.RateLimitedException;
import io.github.rubenix.yttranscriber.exception.VideoTooLongException;
import io.github.rubenix.yttranscriber.limiter.CapacityGuard;
import io.github.rubenix.yttranscriber.limiter.UsageLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscriptionServiceTest {

    private static final ProcessingLimitsProperties LIMITS = new ProcessingLimitsProperties(1200, 3, 60, 2);

    @Mock
    private SourceResolutionService sourceResolutionService;

    @Mock
    private TranscriptionProvider transcriptionProvider;

    @Mock
    private TranslationService translationService;

    private TranscriptionService transcriptionService;

    @BeforeEach
    void setUp() {
        transcriptionService = newService(LIMITS);
    }

    private TranscriptionService newService(ProcessingLimitsProperties limits) {
        return new TranscriptionService(
                sourceResolutionService, transcriptionProvider, new SentenceGrouper(), translationService,
                limits, new UsageLimiter(limits, Clock.systemUTC()), new CapacityGuard(limits));
    }

    @Test
    void rejectsVideosLongerThanTheConfiguredLimit() {
        VideoMetadata video = new VideoMetadata("abc123", "A very long video", 1201);
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, "en", List.of(), TranscriptSource.SPEECH_TO_TEXT));

        assertThatThrownBy(() -> transcriptionService.process("https://youtu.be/abc123", "es", "session-1"))
                .isInstanceOf(VideoTooLongException.class);
    }

    @Test
    void transcribesWhenTheSourceHasNoReadyMadeSegments() {
        VideoMetadata video = new VideoMetadata("abc123", "Title", 300);
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, null, List.of(), TranscriptSource.SPEECH_TO_TEXT));

        List<TranscriptSegment> transcribed = List.of(new TranscriptSegment(0, 0, 4200, "Hello everybody"));
        when(transcriptionProvider.transcribe(any())).thenReturn(new TranscriptionOutcome("en", transcribed));

        List<TranslatedSegment> translated = List.of(new TranslatedSegment(0, 0, 4200, "Hello everybody", "Hola a todos"));
        when(translationService.translate(transcribed, "en", "es")).thenReturn(translated);

        TranscriptionResult result = transcriptionService.process("https://youtu.be/abc123", "es", "session-1");

        assertThat(result.video()).isEqualTo(video);
        assertThat(result.segments()).isEqualTo(translated);
        assertThat(result.source()).isEqualTo(TranscriptSource.SPEECH_TO_TEXT);
    }

    @Test
    void passesTheSourceProviderVerdictOnHowTheTranscriptWasProducedStraightThrough() {
        // Readers are told which of the three produced the text: the uploader's own captions,
        // YouTube's speech recognition, or ours. They fail in noticeably different ways.
        VideoMetadata video = new VideoMetadata("abc123", "Title", 300);
        List<TranscriptSegment> captionSegments = List.of(new TranscriptSegment(0, 0, 4200, "Hello everybody"));
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, "en", captionSegments, TranscriptSource.MANUAL_CAPTIONS));
        when(translationService.translate(captionSegments, "en", "es")).thenReturn(List.of());

        TranscriptionResult result = transcriptionService.process("https://youtu.be/abc123", "es", "session-1");

        assertThat(result.source()).isEqualTo(TranscriptSource.MANUAL_CAPTIONS);
    }

    @Test
    void usesTheLanguageDetectedBySpeechToTextRatherThanAnyUpstreamHint() {
        // The source provider only ever passes a *hint* (e.g. yt-dlp's declared video language,
        // which can be wrong or absent) when there are no captions to derive it from -- real STT
        // detects the language itself, and that's what must end up in the final result.
        VideoMetadata video = new VideoMetadata("abc123", "Title", 300);
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, "en", List.of(), TranscriptSource.SPEECH_TO_TEXT));

        List<TranscriptSegment> transcribed = List.of(new TranscriptSegment(0, 0, 4200, "Hola a todos"));
        when(transcriptionProvider.transcribe(any())).thenReturn(new TranscriptionOutcome("es", transcribed));
        when(translationService.translate(transcribed, "es", "en")).thenReturn(List.of());

        TranscriptionResult result = transcriptionService.process("https://youtu.be/abc123", "en", "session-1");

        assertThat(result.sourceLanguage()).isEqualTo("es");
    }

    @Test
    void normalizesARegionTaggedSourceLanguageToItsPrimarySubtag() {
        // Caption tracks can carry a region/script subtag ("pt-BR"); the result -- and the
        // same-language check inside TranslationService -- must see the bare primary subtag so
        // it's directly comparable to targetLanguage.
        VideoMetadata video = new VideoMetadata("abc123", "Title", 300);
        List<TranscriptSegment> captionSegments = List.of(new TranscriptSegment(0, 0, 4200, "Ola"));
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, "pt-BR", captionSegments, TranscriptSource.MANUAL_CAPTIONS));
        when(translationService.translate(captionSegments, "pt", "es")).thenReturn(List.of());

        TranscriptionResult result = transcriptionService.process("https://youtu.be/abc123", "es", "session-1");

        assertThat(result.sourceLanguage()).isEqualTo("pt");
    }

    @Test
    void skipsTranscriptionWhenTheSourceAlreadyProvidesSegments() {
        VideoMetadata video = new VideoMetadata("abc123", "Title", 300);
        List<TranscriptSegment> captionSegments = List.of(new TranscriptSegment(0, 0, 4200, "Hello everybody"));
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, "en", captionSegments, TranscriptSource.MANUAL_CAPTIONS));
        when(translationService.translate(captionSegments, "en", "es")).thenReturn(List.of());

        transcriptionService.process("https://youtu.be/abc123", "es", "session-1");

        verify(transcriptionProvider, never()).transcribe(any());
    }

    @Test
    void rejectsOnceTheSessionsRequestBudgetIsExhausted() {
        ProcessingLimitsProperties tightLimits = new ProcessingLimitsProperties(1200, 1, 60, 2);
        TranscriptionService service = newService(tightLimits);
        VideoMetadata video = new VideoMetadata("abc123", "Title", 300);
        List<TranscriptSegment> captionSegments = List.of(new TranscriptSegment(0, 0, 4200, "Hello everybody"));
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, "en", captionSegments, TranscriptSource.MANUAL_CAPTIONS));
        when(translationService.translate(captionSegments, "en", "es")).thenReturn(List.of());

        service.process("https://youtu.be/abc123", "es", "session-1");

        assertThatThrownBy(() -> service.process("https://youtu.be/abc123", "es", "session-1"))
                .isInstanceOf(RateLimitedException.class);
        verify(sourceResolutionService, times(1)).resolve("https://youtu.be/abc123");
    }

    @Test
    void doesNotCountAgainstAnotherSessionsRequestBudget() {
        ProcessingLimitsProperties tightLimits = new ProcessingLimitsProperties(1200, 1, 60, 2);
        TranscriptionService service = newService(tightLimits);
        VideoMetadata video = new VideoMetadata("abc123", "Title", 300);
        List<TranscriptSegment> captionSegments = List.of(new TranscriptSegment(0, 0, 4200, "Hello everybody"));
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, "en", captionSegments, TranscriptSource.MANUAL_CAPTIONS));
        when(translationService.translate(captionSegments, "en", "es")).thenReturn(List.of());

        service.process("https://youtu.be/abc123", "es", "session-1");

        assertThat(service.process("https://youtu.be/abc123", "es", "session-2")).isNotNull();
    }

    @Test
    void reportsProgressThroughTheCaptionPathWithoutATranscribingStage() {
        VideoMetadata video = new VideoMetadata("abc123", "Title", 300);
        List<TranscriptSegment> captionSegments = List.of(new TranscriptSegment(0, 0, 4200, "Hello everybody"));
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, "en", captionSegments, TranscriptSource.MANUAL_CAPTIONS));
        when(translationService.translate(captionSegments, "en", "es")).thenReturn(List.of());

        List<ProcessingStage> reported = new ArrayList<>();
        transcriptionService.process("https://youtu.be/abc123", "es", "session-1", reported::add);

        assertThat(reported).containsExactly(
                ProcessingStage.RESOLVING_VIDEO, ProcessingStage.TRANSLATING, ProcessingStage.PREPARING_RESULT);
    }

    @Test
    void reportsATranscribingStageWhenFallingBackToSpeechToText() {
        VideoMetadata video = new VideoMetadata("abc123", "Title", 300);
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, null, List.of(), TranscriptSource.SPEECH_TO_TEXT));
        List<TranscriptSegment> transcribed = List.of(new TranscriptSegment(0, 0, 4200, "Hello everybody"));
        when(transcriptionProvider.transcribe(any())).thenReturn(new TranscriptionOutcome("en", transcribed));
        when(translationService.translate(transcribed, "en", "es")).thenReturn(List.of());

        List<ProcessingStage> reported = new ArrayList<>();
        transcriptionService.process("https://youtu.be/abc123", "es", "session-1", reported::add);

        assertThat(reported).containsExactly(
                ProcessingStage.RESOLVING_VIDEO, ProcessingStage.TRANSCRIBING,
                ProcessingStage.TRANSLATING, ProcessingStage.PREPARING_RESULT);
    }

    @Test
    void doesNotSpendTheSessionsRequestBudgetWhenRejectedForCapacity() throws Exception {
        // Being turned away because the server is busy is the server's problem, not the caller's:
        // it must not cost one of their few hourly requests, or a couple of retries on a busy
        // server would lock them out for an hour without ever having transcribed anything.
        ProcessingLimitsProperties oneAtATime = new ProcessingLimitsProperties(1200, 1, 60, 1);
        UsageLimiter usageLimiter = new UsageLimiter(oneAtATime, Clock.systemUTC());
        CapacityGuard capacityGuard = new CapacityGuard(oneAtATime);
        TranscriptionService service = new TranscriptionService(
                sourceResolutionService, transcriptionProvider, new SentenceGrouper(), translationService,
                oneAtATime, usageLimiter, capacityGuard);

        CountDownLatch permitHeld = new CountDownLatch(1);
        CountDownLatch releasePermit = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> capacityGuard.runWithinCapacity(() -> {
            permitHeld.countDown();
            awaitQuietly(releasePermit);
            return null;
        }));
        permitHeld.await();

        assertThatThrownBy(() -> service.process("https://youtu.be/abc123", "es", "session-1"))
                .isInstanceOf(RateLimitedException.class);

        releasePermit.countDown();
        holder.join();

        verify(sourceResolutionService, never()).resolve(any());
        assertThatCode(() -> usageLimiter.checkAndRecordRequest("session-1")).doesNotThrowAnyException();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void rejectsOnceTheSessionsAudioMinutesBudgetIsExhausted() {
        ProcessingLimitsProperties tightLimits = new ProcessingLimitsProperties(1200, 100, 4, 2);
        TranscriptionService service = newService(tightLimits);
        VideoMetadata video = new VideoMetadata("abc123", "A five minute video", 300);
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, "en", List.of(new TranscriptSegment(0, 0, 4200, "Hello everybody")), TranscriptSource.MANUAL_CAPTIONS));

        assertThatThrownBy(() -> service.process("https://youtu.be/abc123", "es", "session-1"))
                .isInstanceOf(RateLimitedException.class);
    }
}
