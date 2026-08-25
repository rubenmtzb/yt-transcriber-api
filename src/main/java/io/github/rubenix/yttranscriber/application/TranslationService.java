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

    public List<TranslatedSegment> translate(List<TranscriptSegment> segments, String targetLanguage) {
        return translationProvider.translate(new TranslationRequest(segments, targetLanguage));
    }
}
