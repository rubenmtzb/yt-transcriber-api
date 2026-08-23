package io.github.rubenix.yttranscriber.domain.translation;

import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;

import java.util.List;

public record TranslationRequest(List<TranscriptSegment> segments, String sourceLanguage, String targetLanguage) {
}
