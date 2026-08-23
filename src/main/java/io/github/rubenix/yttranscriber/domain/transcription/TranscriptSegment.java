package io.github.rubenix.yttranscriber.domain.transcription;

public record TranscriptSegment(int sequence, long startMs, long endMs, String text) {
}
