package io.github.rubenix.yttranscriber.domain.transcription;

/**
 * One word with the moment it is actually spoken.
 *
 * Both sources can supply these and neither was being read: YouTube's {@code json3} carries a
 * {@code tOffsetMs} per word, and whisper.cpp reports per-token offsets. Without them the
 * read-along highlight has to guess where each word falls by spreading a line's duration across
 * its characters, which drifts noticeably inside a long line.
 */
public record TimedWord(String text, long startMs, long endMs) {
}
