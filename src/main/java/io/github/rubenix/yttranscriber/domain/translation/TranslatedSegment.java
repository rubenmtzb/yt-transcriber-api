package io.github.rubenix.yttranscriber.domain.translation;

import io.github.rubenix.yttranscriber.domain.transcription.TimedWord;

import java.util.List;

/**
 * A line paired with its translation. {@code words} times the *source* text only: a translation
 * reorders and re-splits what was said, so there is no honest per-word mapping onto it.
 */
public record TranslatedSegment(
        int sequence,
        long startMs,
        long endMs,
        String sourceText,
        String translatedText,
        List<TimedWord> words) {

    public TranslatedSegment {
        words = words == null ? List.of() : List.copyOf(words);
    }

    public TranslatedSegment(int sequence, long startMs, long endMs, String sourceText, String translatedText) {
        this(sequence, startMs, endMs, sourceText, translatedText, List.of());
    }
}
