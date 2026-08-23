package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.config.ProcessingLimitsProperties;
import io.github.rubenix.yttranscriber.domain.source.SourceResolution;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionProvider;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionRequest;
import io.github.rubenix.yttranscriber.domain.translation.TranslatedSegment;
import io.github.rubenix.yttranscriber.exception.VideoTooLongException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates the synchronous transcription use case: resolve source, enforce the video
 * duration guardrail, transcribe if the source has no ready-made captions, then translate.
 */
@Service
public class TranscriptionService {

    private final SourceResolutionService sourceResolutionService;
    private final TranscriptionProvider transcriptionProvider;
    private final TranslationService translationService;
    private final ProcessingLimitsProperties limits;

    public TranscriptionService(SourceResolutionService sourceResolutionService,
                                 TranscriptionProvider transcriptionProvider,
                                 TranslationService translationService,
                                 ProcessingLimitsProperties limits) {
        this.sourceResolutionService = sourceResolutionService;
        this.transcriptionProvider = transcriptionProvider;
        this.translationService = translationService;
        this.limits = limits;
    }

    public TranscriptionResult process(String youtubeUrl, String targetLanguage) {
        SourceResolution resolution = sourceResolutionService.resolve(youtubeUrl);
        requireWithinDurationLimit(resolution.video().durationSeconds());

        List<TranscriptSegment> segments = resolution.segments().isEmpty()
                ? transcriptionProvider.transcribe(new TranscriptionRequest(resolution.video(), resolution.sourceLanguage()))
                : resolution.segments();

        List<TranslatedSegment> translated = translationService.translate(segments, resolution.sourceLanguage(), targetLanguage);

        return new TranscriptionResult(resolution.video(), resolution.sourceLanguage(), targetLanguage, translated);
    }

    private void requireWithinDurationLimit(long durationSeconds) {
        if (durationSeconds > limits.maxVideoDurationSeconds()) {
            throw new VideoTooLongException(
                    "The video exceeds the maximum allowed duration of %d seconds.".formatted(limits.maxVideoDurationSeconds()));
        }
    }
}
