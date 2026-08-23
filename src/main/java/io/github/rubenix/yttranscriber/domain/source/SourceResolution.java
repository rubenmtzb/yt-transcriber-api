package io.github.rubenix.yttranscriber.domain.source;

import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;

import java.util.List;

/**
 * Result of resolving a video source. {@code segments} is empty when the source does not
 * expose a ready-made transcript (e.g. captions) and a separate transcription step is required.
 */
public record SourceResolution(VideoMetadata video, String sourceLanguage, List<TranscriptSegment> segments) {
}
