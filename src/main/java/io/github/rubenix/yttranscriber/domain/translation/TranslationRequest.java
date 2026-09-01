package io.github.rubenix.yttranscriber.domain.translation;

import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;

import java.util.List;

/**
 * Carries no source language on purpose. The provider detects it, and the codes available here
 * come from YouTube's caption tracks ("pt-BR", "es-419", "zh-Hans"), whose vocabulary does not map
 * cleanly onto what a translation API accepts as an explicit source -- passing one through
 * unmapped would turn a video that translates fine today into a hard request failure. Add it back
 * alongside a real code mapping if a provider ever needs to be told.
 */
public record TranslationRequest(List<TranscriptSegment> segments, String targetLanguage) {
}
