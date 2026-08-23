package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.config.ProcessingLimitsProperties;
import io.github.rubenix.yttranscriber.domain.source.SourceResolution;
import io.github.rubenix.yttranscriber.domain.source.VideoMetadata;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionProvider;
import io.github.rubenix.yttranscriber.domain.translation.TranslatedSegment;
import io.github.rubenix.yttranscriber.exception.VideoTooLongException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscriptionServiceTest {

    private static final ProcessingLimitsProperties LIMITS = new ProcessingLimitsProperties(1200, 3, 60);

    @Mock
    private SourceResolutionService sourceResolutionService;

    @Mock
    private TranscriptionProvider transcriptionProvider;

    @Mock
    private TranslationService translationService;

    private TranscriptionService transcriptionService;

    @BeforeEach
    void setUp() {
        transcriptionService = new TranscriptionService(sourceResolutionService, transcriptionProvider, translationService, LIMITS);
    }

    @Test
    void rejectsVideosLongerThanTheConfiguredLimit() {
        VideoMetadata video = new VideoMetadata("abc123", "A very long video", 1201);
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, "en", List.of()));

        assertThatThrownBy(() -> transcriptionService.process("https://youtu.be/abc123", "es"))
                .isInstanceOf(VideoTooLongException.class);
    }

    @Test
    void transcribesWhenTheSourceHasNoReadyMadeSegments() {
        VideoMetadata video = new VideoMetadata("abc123", "Title", 300);
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, "en", List.of()));

        List<TranscriptSegment> transcribed = List.of(new TranscriptSegment(0, 0, 4200, "Hello everybody"));
        when(transcriptionProvider.transcribe(any())).thenReturn(transcribed);

        List<TranslatedSegment> translated = List.of(new TranslatedSegment(0, 0, 4200, "Hello everybody", "Hola a todos"));
        when(translationService.translate(transcribed, "en", "es")).thenReturn(translated);

        TranscriptionResult result = transcriptionService.process("https://youtu.be/abc123", "es");

        assertThat(result.video()).isEqualTo(video);
        assertThat(result.segments()).isEqualTo(translated);
    }

    @Test
    void skipsTranscriptionWhenTheSourceAlreadyProvidesSegments() {
        VideoMetadata video = new VideoMetadata("abc123", "Title", 300);
        List<TranscriptSegment> captionSegments = List.of(new TranscriptSegment(0, 0, 4200, "Hello everybody"));
        when(sourceResolutionService.resolve("https://youtu.be/abc123"))
                .thenReturn(new SourceResolution(video, "en", captionSegments));
        when(translationService.translate(captionSegments, "en", "es")).thenReturn(List.of());

        transcriptionService.process("https://youtu.be/abc123", "es");

        verify(transcriptionProvider, never()).transcribe(any());
    }
}
