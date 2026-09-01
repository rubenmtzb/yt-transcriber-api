package io.github.rubenix.yttranscriber.bench;

import io.github.rubenix.yttranscriber.application.SentenceGrouper;
import io.github.rubenix.yttranscriber.config.ProcessingLimitsProperties;
import io.github.rubenix.yttranscriber.domain.transcription.TimedWord;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.limiter.UsageLimiter;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Throwaway timing harness -- run by hand, never part of the suite (timings make flaky tests). */
public final class Benchmark {

    public static void main(String[] args) {
        groupingCost();
        limiterCost();
    }

    private static void groupingCost() {
        SentenceGrouper grouper = new SentenceGrouper();
        for (int cues : new int[] {500, 5_000, 50_000}) {
            List<TranscriptSegment> input = new ArrayList<>(cues);
            for (int i = 0; i < cues; i++) {
                long start = i * 2_000L;
                List<TimedWord> words = List.of(
                        new TimedWord("una", start, start + 600),
                        new TimedWord(" linea", start + 600, start + 1_400),
                        new TimedWord(" corta", start + 1_400, start + 2_000));
                input.add(new TranscriptSegment(i, start, start + 2_000, "una linea corta", words));
            }
            grouper.group(input);
            long t = System.nanoTime();
            int lines = grouper.group(input).size();
            System.out.printf("SentenceGrouper: %,d cues -> %,d lineas en %.1f ms%n",
                    cues, lines, (System.nanoTime() - t) / 1e6);
        }
    }

    private static void limiterCost() {
        var limits = new ProcessingLimitsProperties(1200, 1_000_000, 1_000_000, 2);
        for (int sessions : new int[] {1_000, 10_000, 100_000}) {
            UsageLimiter limiter = new UsageLimiter(limits, Clock.systemUTC());
            for (int i = 0; i < sessions; i++) {
                limiter.checkAndRecordRequest("session-" + i);
            }
            long t = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                limiter.checkAndRecordRequest("hot-session");
            }
            System.out.printf("UsageLimiter: %,d sesiones vivas -> %.3f ms por peticion%n",
                    sessions, (System.nanoTime() - t) / 1e6 / 100);
        }
    }
}
