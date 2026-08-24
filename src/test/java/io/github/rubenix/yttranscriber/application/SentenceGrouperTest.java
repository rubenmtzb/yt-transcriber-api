package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceGrouperTest {

    private final SentenceGrouper grouper = new SentenceGrouper();

    @Test
    void returnsEmptyListForEmptyInput() {
        assertThat(grouper.group(List.of())).isEmpty();
    }

    @Test
    void leavesAWellPunctuatedSingleSegmentUnchanged() {
        List<TranscriptSegment> input = List.of(new TranscriptSegment(0, 0, 4200, "Hello everybody."));

        List<TranscriptSegment> result = grouper.group(input);

        assertThat(result).containsExactly(new TranscriptSegment(0, 0, 4200, "Hello everybody."));
    }

    @Test
    void mergesConsecutiveCuesSplitMidSentenceIntoOneSentence() {
        List<TranscriptSegment> input = List.of(
                new TranscriptSegment(0, 0, 1800, "I feel like y'all need some energy right"),
                new TranscriptSegment(1, 1800, 2200, "now."));

        List<TranscriptSegment> result = grouper.group(input);

        assertThat(result).containsExactly(
                new TranscriptSegment(0, 0, 2200, "I feel like y'all need some energy right now."));
    }

    @Test
    void mergesTheRealWorldMissYourTouchCase() {
        List<TranscriptSegment> input = List.of(
                new TranscriptSegment(0, 0, 2000, "Cuz when I'm away from you, I miss your"),
                new TranscriptSegment(1, 2000, 2500, "touch."));

        List<TranscriptSegment> result = grouper.group(input);

        assertThat(result).containsExactly(
                new TranscriptSegment(0, 0, 2500, "Cuz when I'm away from you, I miss your touch."));
    }

    @Test
    void startsANewGroupAfterARealPauseEvenWithoutPunctuation() {
        List<TranscriptSegment> input = List.of(
                new TranscriptSegment(0, 0, 1000, "no punctuation here"),
                new TranscriptSegment(1, 3500, 4500, "and a new thought after a pause"));

        List<TranscriptSegment> result = grouper.group(input);

        assertThat(result).containsExactly(
                new TranscriptSegment(0, 0, 1000, "no punctuation here"),
                new TranscriptSegment(1, 3500, 4500, "and a new thought after a pause"));
    }

    @Test
    void closesAGroupOnceTheWordCapIsReachedEvenWithoutPunctuationOrPauses() {
        List<TranscriptSegment> input = List.of(
                new TranscriptSegment(0, 0, 500, "w1 w2 w3 w4 w5"),
                new TranscriptSegment(1, 500, 1000, "w6 w7 w8 w9 w10"),
                new TranscriptSegment(2, 1000, 1500, "w11 w12 w13 w14 w15"),
                new TranscriptSegment(3, 1500, 2000, "w16 w17 w18 w19 w20"),
                new TranscriptSegment(4, 2000, 2500, "w21 w22 w23 w24 w25"),
                new TranscriptSegment(5, 2500, 3000, "w26 w27 w28 w29 w30"));

        List<TranscriptSegment> result = grouper.group(input);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).text().split("\\s+")).hasSize(25);
        assertThat(result.get(0).startMs()).isZero();
        assertThat(result.get(0).endMs()).isEqualTo(2500);
        assertThat(result.get(1).text().split("\\s+")).hasSize(5);
    }

    @Test
    void skipsBlankSegmentsWithoutBreakingTheSurroundingGroup() {
        List<TranscriptSegment> input = List.of(
                new TranscriptSegment(0, 0, 500, "Hello"),
                new TranscriptSegment(1, 500, 500, "   "),
                new TranscriptSegment(2, 500, 1000, "world."));

        List<TranscriptSegment> result = grouper.group(input);

        assertThat(result).containsExactly(new TranscriptSegment(0, 0, 1000, "Hello world."));
    }

    @Test
    void resequencesMergedGroupsStartingAtZero() {
        List<TranscriptSegment> input = List.of(
                new TranscriptSegment(5, 0, 1000, "First sentence."),
                new TranscriptSegment(6, 1000, 2000, "Second sentence."));

        List<TranscriptSegment> result = grouper.group(input);

        assertThat(result).extracting(TranscriptSegment::sequence).containsExactly(0, 1);
    }
}
