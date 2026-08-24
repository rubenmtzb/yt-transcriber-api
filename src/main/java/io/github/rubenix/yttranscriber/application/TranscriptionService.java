package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.config.ProcessingLimitsProperties;
import io.github.rubenix.yttranscriber.domain.source.SourceResolution;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionProvider;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionRequest;
import io.github.rubenix.yttranscriber.domain.translation.TranslatedSegment;
import io.github.rubenix.yttranscriber.exception.VideoTooLongException;
import io.github.rubenix.yttranscriber.limiter.CapacityGuard;
import io.github.rubenix.yttranscriber.limiter.UsageLimiter;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates the synchronous transcription use case: enforce the caller's request budget,
 * reserve a slot in the global capacity guard, resolve source, enforce the video duration
 * guardrail and the caller's audio-minutes budget, transcribe if the source has no ready-made
 * captions, then translate.
 */
@Service
public class TranscriptionService {

    private final SourceResolutionService sourceResolutionService;
    private final TranscriptionProvider transcriptionProvider;
    private final SentenceGrouper sentenceGrouper;
    private final TranslationService translationService;
    private final ProcessingLimitsProperties limits;
    private final UsageLimiter usageLimiter;
    private final CapacityGuard capacityGuard;

    public TranscriptionService(SourceResolutionService sourceResolutionService,
                                 TranscriptionProvider transcriptionProvider,
                                 SentenceGrouper sentenceGrouper,
                                 TranslationService translationService,
                                 ProcessingLimitsProperties limits,
                                 UsageLimiter usageLimiter,
                                 CapacityGuard capacityGuard) {
        this.sourceResolutionService = sourceResolutionService;
        this.transcriptionProvider = transcriptionProvider;
        this.sentenceGrouper = sentenceGrouper;
        this.translationService = translationService;
        this.limits = limits;
        this.usageLimiter = usageLimiter;
        this.capacityGuard = capacityGuard;
    }

    public TranscriptionResult process(String youtubeUrl, String targetLanguage, String sessionId) {
        usageLimiter.checkAndRecordRequest(sessionId);

        return capacityGuard.runWithinCapacity(() -> {
            SourceResolution resolution = sourceResolutionService.resolve(youtubeUrl);
            requireWithinDurationLimit(resolution.video().durationSeconds());
            usageLimiter.checkAndRecordAudioMinutes(sessionId, resolution.video().durationSeconds());

            List<TranscriptSegment> segments = resolution.segments().isEmpty()
                    ? transcriptionProvider.transcribe(new TranscriptionRequest(resolution.video(), resolution.sourceLanguage()))
                    : resolution.segments();

            List<TranscriptSegment> grouped = sentenceGrouper.group(segments);
            List<TranslatedSegment> translated = translationService.translate(grouped, resolution.sourceLanguage(), targetLanguage);

            return new TranscriptionResult(resolution.video(), resolution.sourceLanguage(), targetLanguage, translated);
        });
    }

    private void requireWithinDurationLimit(long durationSeconds) {
        if (durationSeconds > limits.maxVideoDurationSeconds()) {
            throw new VideoTooLongException(
                    "The video exceeds the maximum allowed duration of %d seconds.".formatted(limits.maxVideoDurationSeconds()));
        }
    }
}
