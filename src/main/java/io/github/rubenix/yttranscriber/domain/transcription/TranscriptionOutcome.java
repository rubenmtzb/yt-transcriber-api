package io.github.rubenix.yttranscriber.domain.transcription;

import java.util.List;

/**
 * Result of running real Speech-to-Text: unlike caption-based sources, the language isn't known
 * upfront -- the provider detects it as part of transcribing.
 */
public record TranscriptionOutcome(String language, List<TranscriptSegment> segments) {
}
