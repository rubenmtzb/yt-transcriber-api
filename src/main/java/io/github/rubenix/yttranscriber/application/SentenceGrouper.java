package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Merges YouTube's short, line-wrap caption cues into sentence-level units before translation.
 * Source captions are cut for on-screen width, not grammar, so translating each cue independently
 * loses cross-cue context (e.g. "I miss your" / "touch." mistranslated in isolation because DeepL
 * never sees the two together). Cues rarely carry punctuation reliably (auto-generated captions
 * often have none at all), so the boundary is a hybrid: close a group on a sentence-ending mark
 * when present, otherwise on a real pause between cues, otherwise once a size cap is hit so a long
 * unpunctuated stretch can't merge into one unreadable block.
 */
@Component
public class SentenceGrouper {

    private static final int MAX_GROUP_WORDS = 25;
    private static final long MAX_GROUP_SPAN_MS = 15_000;
    private static final long GAP_BREAK_MS = 1_500;

    public List<TranscriptSegment> group(List<TranscriptSegment> segments) {
        List<TranscriptSegment> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        long groupStart = 0;
        long groupEnd = 0;
        int wordCount = 0;
        Long previousEndMs = null;

        for (TranscriptSegment segment : segments) {
            String text = segment.text().strip();
            if (text.isEmpty()) {
                continue;
            }

            boolean startingNewGroup = buffer.isEmpty();
            boolean gapBreak = !startingNewGroup
                    && previousEndMs != null
                    && (segment.startMs() - previousEndMs) >= GAP_BREAK_MS;
            if (gapBreak) {
                flush(merged, buffer, groupStart, groupEnd);
                wordCount = 0;
                startingNewGroup = true;
            }

            if (startingNewGroup) {
                groupStart = segment.startMs();
            } else {
                buffer.append(' ');
            }
            buffer.append(text);
            groupEnd = segment.endMs();
            wordCount += countWords(text);
            previousEndMs = segment.endMs();

            boolean overCap = wordCount >= MAX_GROUP_WORDS || (groupEnd - groupStart) >= MAX_GROUP_SPAN_MS;
            if (endsWithSentenceTerminator(text) || overCap) {
                flush(merged, buffer, groupStart, groupEnd);
                wordCount = 0;
            }
        }

        if (!buffer.isEmpty()) {
            flush(merged, buffer, groupStart, groupEnd);
        }

        return merged;
    }

    /** Closes the group accumulated in {@code buffer}, appending it to {@code merged} and resetting it. */
    private static void flush(List<TranscriptSegment> merged, StringBuilder buffer, long groupStart, long groupEnd) {
        merged.add(new TranscriptSegment(merged.size(), groupStart, groupEnd, buffer.toString()));
        buffer.setLength(0);
    }

    /** Counts words in already-stripped text, so no further trimming is needed here. */
    private static int countWords(String text) {
        return text.split("\\s+").length;
    }

    private static boolean endsWithSentenceTerminator(String text) {
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?';
    }
}
