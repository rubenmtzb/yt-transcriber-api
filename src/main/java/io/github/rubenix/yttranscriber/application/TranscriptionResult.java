package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.domain.source.VideoMetadata;
import io.github.rubenix.yttranscriber.domain.translation.TranslatedSegment;

import java.util.List;

public record TranscriptionResult(
        VideoMetadata video,
        String sourceLanguage,
        String targetLanguage,
        List<TranslatedSegment> segments) {
}
