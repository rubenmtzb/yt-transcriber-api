package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.translation.TranslatedSegment;
import io.github.rubenix.yttranscriber.domain.translation.TranslationProvider;
import io.github.rubenix.yttranscriber.domain.translation.TranslationRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TranslationService {

    private final TranslationProvider translationProvider;

    public TranslationService(TranslationProvider translationProvider) {
        this.translationProvider = translationProvider;
    }

    public List<TranslatedSegment> translate(List<TranscriptSegment> segments, String sourceLanguage, String targetLanguage) {
        if (sameLanguage(sourceLanguage, targetLanguage)) {
            // Calling DeepL to "translate" a video into the language it's already in would just
            // spend this demo's shared monthly quota for a no-op -- echo the source text back
            // instead of hitting the provider at all. sourceLanguage arrives already normalized
            // to a bare primary subtag (see TranscriptionService), so a plain case-insensitive
            // compare against targetLanguage (always exactly two letters, per the request DTO) is
            // enough here.
            return segments.stream()
                    .map(segment -> new TranslatedSegment(
                            segment.sequence(), segment.startMs(), segment.endMs(), segment.text(), segment.text()))
                    .toList();
        }
        // sourceLanguage is only used for the same-language check above -- it never reaches
        // TranslationRequest, since DeepL is left to auto-detect and no code path maps YouTube's
        // caption-track language codes onto DeepL's accepted source_lang vocabulary.
        return translationProvider.translate(new TranslationRequest(segments, targetLanguage));
    }

    private boolean sameLanguage(String sourceLanguage, String targetLanguage) {
        return sourceLanguage != null && targetLanguage != null && sourceLanguage.equalsIgnoreCase(targetLanguage);
    }
}
