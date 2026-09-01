package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.domain.transcription.TimedWord;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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

    private static final Pattern BRACKETED_ANNOTATION = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern WORD_CHARACTER = Pattern.compile("[\\p{L}\\p{N}]");

    public List<TranscriptSegment> group(List<TranscriptSegment> segments) {
        List<TranscriptSegment> merged = new ArrayList<>();
        List<TimedWord> words = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        long groupStart = 0;
        long groupEnd = 0;
        Long spokenStart = null;
        Long spokenEnd = null;
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
                merged.add(close(merged.size(), groupStart, groupEnd, spokenStart, spokenEnd, buffer, words));
                buffer.setLength(0);
                words.clear();
                wordCount = 0;
                spokenStart = null;
                spokenEnd = null;
                startingNewGroup = true;
            }

            if (startingNewGroup) {
                groupStart = segment.startMs();
            } else {
                buffer.append(' ');
            }
            buffer.append(text);
            words.addAll(segment.words());
            groupEnd = segment.endMs();
            wordCount += countWords(text);
            previousEndMs = segment.endMs();

            if (carriesSpeech(text)) {
                if (spokenStart == null) {
                    spokenStart = segment.startMs();
                }
                spokenEnd = segment.endMs();
            }

            boolean overCap = wordCount >= MAX_GROUP_WORDS || (groupEnd - groupStart) >= MAX_GROUP_SPAN_MS;
            if (endsWithSentenceTerminator(text) || overCap) {
                merged.add(close(merged.size(), groupStart, groupEnd, spokenStart, spokenEnd, buffer, words));
                buffer.setLength(0);
                words.clear();
                wordCount = 0;
                spokenStart = null;
                spokenEnd = null;
            }
        }

        if (!buffer.isEmpty()) {
            merged.add(close(merged.size(), groupStart, groupEnd, spokenStart, spokenEnd, buffer, words));
        }

        return merged;
    }

    /**
     * Times the merged line by the cues that actually carry words, not by every cue swept into it.
     *
     * A silent stretch is still transcribed: Whisper hands back a cue reading "♪" for a song's
     * instrumental intro, captions hand back "[Music]". Merged in, those drag the line's start
     * backwards -- one real case gave a line spanning 0-22s whose singing only begins at 12s, so
     * anything paced by the line's duration was already two thirds through the lyrics by the time
     * the first word was sung. The annotation stays in the text; it just stops setting the clock.
     */
    private TranscriptSegment close(int sequence, long groupStart, long groupEnd, Long spokenStart,
                                     Long spokenEnd, StringBuilder buffer, List<TimedWord> words) {
        long startMs = spokenStart != null ? spokenStart : groupStart;
        long endMs = spokenEnd != null ? spokenEnd : groupEnd;
        return new TranscriptSegment(sequence, startMs, endMs, buffer.toString(), List.copyOf(words));
    }

    private static boolean carriesSpeech(String text) {
        String withoutAnnotations = BRACKETED_ANNOTATION.matcher(text).replaceAll("");
        return WORD_CHARACTER.matcher(withoutAnnotations).find();
    }

    private static int countWords(String text) {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    private static boolean endsWithSentenceTerminator(String text) {
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?';
    }
}
