package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.config.ProcessingLimitsProperties;
import io.github.rubenix.yttranscriber.domain.source.SourceResolution;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionOutcome;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionProvider;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionRequest;
import io.github.rubenix.yttranscriber.domain.translation.TranslatedSegment;
import io.github.rubenix.yttranscriber.exception.VideoTooLongException;
import io.github.rubenix.yttranscriber.limiter.CapacityGuard;
import io.github.rubenix.yttranscriber.limiter.UsageLimiter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Orchestrates the synchronous transcription use case: reserve a slot in the global capacity
 * guard, enforce the caller's request budget, resolve source, enforce the video duration
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
    private final UsageLimiter sessionUsageLimiter;
    private final UsageLimiter ipUsageLimiter;
    private final CapacityGuard capacityGuard;

    public TranscriptionService(SourceResolutionService sourceResolutionService,
                                 TranscriptionProvider transcriptionProvider,
                                 SentenceGrouper sentenceGrouper,
                                 TranslationService translationService,
                                 ProcessingLimitsProperties limits,
                                 @Qualifier("sessionUsageLimiter") UsageLimiter sessionUsageLimiter,
                                 @Qualifier("ipUsageLimiter") UsageLimiter ipUsageLimiter,
                                 CapacityGuard capacityGuard) {
        this.sourceResolutionService = sourceResolutionService;
        this.transcriptionProvider = transcriptionProvider;
        this.sentenceGrouper = sentenceGrouper;
        this.translationService = translationService;
        this.limits = limits;
        this.sessionUsageLimiter = sessionUsageLimiter;
        this.ipUsageLimiter = ipUsageLimiter;
        this.capacityGuard = capacityGuard;
    }

    public TranscriptionResult process(String youtubeUrl, String targetLanguage, String sessionId, String clientIp) {
        return process(youtubeUrl, targetLanguage, sessionId, clientIp, ProgressListener.NOOP);
    }

    /**
     * Same use case, reporting real progress as it happens (used by the SSE streaming endpoint).
     * VALIDATING_URL isn't reported here -- it's already done by the time this method runs
     * (request-shape validation happens at the controller boundary) -- the caller emits it itself
     * the moment the stream opens.
     */
    public TranscriptionResult process(String youtubeUrl, String targetLanguage, String sessionId, String clientIp,
                                        ProgressListener progress) {
        return capacityGuard.runWithinCapacity(() -> {
            // Charged only once a capacity permit is actually held. Charging before would spend one
            // of the caller's hourly requests on a "server busy" rejection that did no work at all
            // -- and since RATE_LIMITED is flagged retryable, the UI invites exactly the retries
            // that would burn the rest of the budget on the server's own congestion.
            // Session bucket first, address bucket second. Either order catches a caller cycling
            // through invented session ids -- their fresh id sails through the first check and the
            // address check is still waiting behind it -- so the order is decided by who pays for a
            // refusal instead. Each bucket records as it checks, so whichever runs first has already
            // charged the caller by the time the second one refuses. Refusals by the session budget
            // are the common ones (three an hour against twelve), and this way they cost only the
            // person who spent them, rather than draining the allowance shared with everyone else
            // behind the same router.
            sessionUsageLimiter.checkAndRecordRequest(sessionId);
            ipUsageLimiter.checkAndRecordRequest(clientIp);

            progress.onStage(ProcessingStage.RESOLVING_VIDEO);
            SourceResolution resolution = sourceResolutionService.resolve(youtubeUrl);
            requireWithinDurationLimit(resolution.video().durationSeconds());
            sessionUsageLimiter.checkAndRecordAudioMinutes(sessionId, resolution.video().durationSeconds());
            ipUsageLimiter.checkAndRecordAudioMinutes(clientIp, resolution.video().durationSeconds());

            String sourceLanguage;
            List<TranscriptSegment> segments;
            if (resolution.segments().isEmpty()) {
                progress.onStage(ProcessingStage.TRANSCRIBING);
                TranscriptionOutcome outcome = transcriptionProvider.transcribe(
                        new TranscriptionRequest(youtubeUrl, resolution.video(), resolution.sourceLanguage()));
                sourceLanguage = outcome.language();
                segments = outcome.segments();
            } else {
                sourceLanguage = resolution.sourceLanguage();
                segments = resolution.segments();
            }
            sourceLanguage = normalizeLanguageCode(sourceLanguage);

            progress.onStage(ProcessingStage.TRANSLATING);
            List<TranscriptSegment> grouped = sentenceGrouper.group(segments);
            List<TranslatedSegment> translated = translationService.translate(grouped, sourceLanguage, targetLanguage);

            progress.onStage(ProcessingStage.PREPARING_RESULT);
            return new TranscriptionResult(resolution.video(), sourceLanguage, targetLanguage, resolution.source(), translated);
        });
    }

    // YouTube caption tracks can carry a region/script subtag ("pt-BR", "es-419"); Whisper's
    // detected language is already a bare code. Normalizing to the primary subtag here -- once,
    // before it's used for the same-language translation check and echoed back in the response --
    // keeps it directly comparable to targetLanguage, which the request DTO restricts to a plain
    // two-letter code.
    private String normalizeLanguageCode(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return languageCode;
        }
        int separator = languageCode.indexOf('-');
        String primary = separator > 0 ? languageCode.substring(0, separator) : languageCode;
        return primary.toLowerCase(Locale.ROOT);
    }

    private void requireWithinDurationLimit(long durationSeconds) {
        if (durationSeconds > limits.maxVideoDurationSeconds()) {
            throw new VideoTooLongException(
                    "The video exceeds the maximum allowed duration of %d seconds.".formatted(limits.maxVideoDurationSeconds()));
        }
    }
}
