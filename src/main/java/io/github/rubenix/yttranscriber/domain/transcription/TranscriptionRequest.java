package io.github.rubenix.yttranscriber.domain.transcription;

import io.github.rubenix.yttranscriber.domain.source.VideoMetadata;

public record TranscriptionRequest(String youtubeUrl, VideoMetadata video, String sourceLanguage) {
}
