package io.github.rubenix.yttranscriber.domain.transcription;

import io.github.rubenix.yttranscriber.domain.source.VideoMetadata;

public record TranscriptionRequest(VideoMetadata video, String sourceLanguage) {
}
