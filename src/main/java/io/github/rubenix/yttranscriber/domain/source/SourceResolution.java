package io.github.rubenix.yttranscriber.domain.source;

import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSource;

import java.util.List;

/**
 * Result of resolving a video source. {@code segments} is empty when the source does not
 * expose a ready-made transcript (e.g. captions) and a separate transcription step is required,
 * which is also what {@code source} reports as {@link TranscriptSource#SPEECH_TO_TEXT}.
 */
public record SourceResolution(
        VideoMetadata video,
        String sourceLanguage,
        List<TranscriptSegment> segments,
        TranscriptSource source) {
}
