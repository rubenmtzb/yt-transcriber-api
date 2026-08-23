package io.github.rubenix.yttranscriber.domain.translation;

public record TranslatedSegment(int sequence, long startMs, long endMs, String sourceText, String translatedText) {
}
