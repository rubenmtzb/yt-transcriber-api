package io.github.rubenix.yttranscriber.api.dto;

import java.util.List;

/**
 * {@code words} times the source text word by word when the provider supplied timings, and is
 * empty otherwise. It is deliberately absent for the translation: a translation reorders and
 * re-splits what was said, so there is no honest word-level mapping onto it.
 */
public record SegmentDto(
        int sequence,
        long startMs,
        long endMs,
        String sourceText,
        String translatedText,
        List<TimedWordDto> words) {
}
