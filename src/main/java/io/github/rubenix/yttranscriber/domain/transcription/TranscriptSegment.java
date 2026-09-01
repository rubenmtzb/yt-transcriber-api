package io.github.rubenix.yttranscriber.domain.transcription;

import java.util.List;

/**
 * A line of transcript. {@code words} carries per-word timings when the source supplies them and
 * is empty when it doesn't (uploader-written captions give timings per line only), so callers must
 * treat it as an optional refinement rather than something to rely on.
 */
public record TranscriptSegment(int sequence, long startMs, long endMs, String text, List<TimedWord> words) {

    public TranscriptSegment {
        words = words == null ? List.of() : List.copyOf(words);
    }

    /** A line whose source gave no per-word timings. */
    public TranscriptSegment(int sequence, long startMs, long endMs, String text) {
        this(sequence, startMs, endMs, text, List.of());
    }
}
