package io.github.rubenix.yttranscriber.integration.youtube;

import tools.jackson.databind.ObjectMapper;
import io.github.rubenix.yttranscriber.domain.source.SourceRequest;
import io.github.rubenix.yttranscriber.domain.source.SourceResolution;
import io.github.rubenix.yttranscriber.domain.source.VideoMetadata;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSource;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import io.github.rubenix.yttranscriber.exception.UnsupportedSourceException;
import io.github.rubenix.yttranscriber.integration.process.ExternalProcessRunner;
import io.github.rubenix.yttranscriber.integration.process.ExternalProcessRunner.ProcessResult;
import io.github.rubenix.yttranscriber.integration.youtube.YtDlpSourceProvider.CaptionTrack;
import io.github.rubenix.yttranscriber.integration.youtube.YtDlpSourceProvider.RawVideoInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YtDlpSourceProviderTest {

    private static final YtDlpProperties PROPERTIES = new YtDlpProperties("yt-dlp", 30);

    @Mock
    private ExternalProcessRunner processRunner;

    private YtDlpSourceProvider sourceProvider;

    @BeforeEach
    void setUp() {
        sourceProvider = new YtDlpSourceProvider(processRunner, new ObjectMapper(), PROPERTIES);
    }

    @Test
    void parsesMetadataFromASuccessfulRun() {
        stub("""
                {"id": "dQw4w9WgXcQ", "title": "Never Gonna Give You Up", "duration": 213, "availability": "public", "is_live": false}
                """, 0);

        VideoMetadata metadata = sourceProvider.fetchMetadata("https://www.youtube.com/watch?v=dQw4w9WgXcQ");

        assertThat(metadata.id()).isEqualTo("dQw4w9WgXcQ");
        assertThat(metadata.title()).isEqualTo("Never Gonna Give You Up");
        assertThat(metadata.durationSeconds()).isEqualTo(213);
    }

    @Test
    void rejectsLiveStreams() {
        stub("""
                {"id": "abc", "title": "Live now", "duration": null, "availability": "public", "is_live": true}
                """, 0);

        assertThatThrownBy(() -> sourceProvider.fetchMetadata("https://www.youtube.com/watch?v=abc"))
                .isInstanceOf(UnsupportedSourceException.class);
    }

    @Test
    void rejectsPrivateOrRestrictedVideos() {
        stub("""
                {"id": "abc", "title": "Private", "duration": 100, "availability": "needs_auth", "is_live": false}
                """, 0);

        assertThatThrownBy(() -> sourceProvider.fetchMetadata("https://www.youtube.com/watch?v=abc"))
                .isInstanceOf(UnsupportedSourceException.class);
    }

    @Test
    void rejectsANullAvailabilityWithoutThrowingNullPointerException() {
        stub("""
                {"id": "abc", "title": "Odd video", "duration": 100, "availability": null, "is_live": false}
                """, 0);

        assertThatThrownBy(() -> sourceProvider.fetchMetadata("https://www.youtube.com/watch?v=abc"))
                .isInstanceOf(UnsupportedSourceException.class);
    }

    @Test
    void treatsANonZeroExitCodeAsAnUnsupportedSource() {
        stub("", 1);

        assertThatThrownBy(() -> sourceProvider.fetchMetadata("https://www.youtube.com/watch?v=missing"))
                .isInstanceOf(UnsupportedSourceException.class);
    }

    @Test
    void treatsUnparseableOutputAsProviderUnavailable() {
        stub("not json", 0);

        assertThatThrownBy(() -> sourceProvider.fetchMetadata("https://www.youtube.com/watch?v=abc"))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    private void stub(String stdout, int exitCode) {
        when(processRunner.run(any(), any())).thenReturn(new ProcessResult(exitCode, stdout, ""));
    }

    // -- caption track selection: which language/track to actually fetch -----------------------
    //
    // Verified empirically against real videos before writing this: an English original exposes
    // its ASR track as "en-orig" in automatic_captions (every other key there is YouTube's own
    // machine translation of it), and a Spanish original ("Despacito") exposes manual subtitles
    // under a plain "es" key alongside legacy community-contribution keys like
    // "es-ES-7eCR4kqQbL4" that must NOT be picked as a language code.

    @Test
    void prefersAManualTrackMatchingTheDeclaredLanguage() {
        RawVideoInfo info = rawInfo("ru", Map.of("ru", List.of(), "en", List.of()), Map.of());

        Optional<CaptionTrack> track = sourceProvider.selectCaptionTrack(info);

        assertThat(track).contains(new CaptionTrack("ru", true));
    }

    @Test
    void ignoresLegacyCommunityContributionKeysWhenPickingAManualTrack() {
        RawVideoInfo info = rawInfo(null, Map.of("es", List.of(), "es-ES-7eCR4kqQbL4", List.of()), Map.of());

        Optional<CaptionTrack> track = sourceProvider.selectCaptionTrack(info);

        assertThat(track).contains(new CaptionTrack("es", true));
    }

    @Test
    void prefersTheOriginalAutomaticTrackOverYoutubesOwnMachineTranslations() {
        RawVideoInfo info = rawInfo("en", Map.of(), Map.of("en-orig", List.of(), "ru", List.of(), "es", List.of()));

        Optional<CaptionTrack> track = sourceProvider.selectCaptionTrack(info);

        assertThat(track).contains(new CaptionTrack("en", false));
    }

    @Test
    void fallsBackToTheDeclaredLanguageInAutomaticCaptionsWhenThereIsNoOrigKey() {
        RawVideoInfo info = rawInfo("ja", Map.of(), Map.of("ja", List.of(), "en", List.of()));

        Optional<CaptionTrack> track = sourceProvider.selectCaptionTrack(info);

        assertThat(track).contains(new CaptionTrack("ja", false));
    }

    @Test
    void findsNothingWhenThereAreNoUsableCaptionsInAnyLanguage() {
        RawVideoInfo info = rawInfo(null, Map.of(), Map.of());

        assertThat(sourceProvider.selectCaptionTrack(info)).isEmpty();
    }

    private RawVideoInfo rawInfo(String language, Map<String, Object> subtitles, Map<String, Object> automaticCaptions) {
        return new RawVideoInfo("id", "title", 100L, "public", false, language, subtitles, automaticCaptions);
    }

    // -- end-to-end wiring: metadata + selection + segment fetch all click together -------------

    @Test
    void resolvesAVideoInARussianOriginalLanguageEndToEnd() {
        when(processRunner.run(any(), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.contains("--print")) {
                return new ProcessResult(0, """
                        {"id": "ru123", "title": "Russian video", "duration": 90, "availability": "public",
                         "is_live": false, "language": "ru", "subtitles": {}, "automatic_captions": {"ru-orig": [], "en": []}}
                        """, "");
            }
            Path dir = Path.of(command.get(command.indexOf("-P") + 1));
            Files.writeString(dir.resolve("ru123.ru.json3"), SAMPLE_JSON3);
            return new ProcessResult(0, "", "");
        });

        SourceResolution resolution = sourceProvider.resolve(new SourceRequest("https://www.youtube.com/watch?v=ru123"));

        assertThat(resolution.sourceLanguage()).isEqualTo("ru");
        assertThat(resolution.segments()).hasSize(2);
        assertThat(resolution.source()).isEqualTo(TranscriptSource.AUTOMATIC_CAPTIONS);
    }

    @Test
    void reportsAnUploaderWrittenTrackAsManualCaptions() {
        // Readers are told which of these produced the text: the uploader's own captions keep real
        // punctuation and spell names right, where YouTube's recognition routinely mangles both.
        when(processRunner.run(any(), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.contains("--print")) {
                return new ProcessResult(0, """
                        {"id": "man123", "title": "Subtitled by hand", "duration": 90, "availability": "public",
                         "is_live": false, "language": "es", "subtitles": {"es": []}, "automatic_captions": {}}
                        """, "");
            }
            Path dir = Path.of(command.get(command.indexOf("-P") + 1));
            Files.writeString(dir.resolve("man123.es.json3"), SAMPLE_JSON3);
            return new ProcessResult(0, "", "");
        });

        SourceResolution resolution = sourceProvider.resolve(new SourceRequest("https://www.youtube.com/watch?v=man123"));

        assertThat(resolution.source()).isEqualTo(TranscriptSource.MANUAL_CAPTIONS);
    }

    @Test
    void returnsEmptySegmentsInsteadOfThrowingWhenThereAreNoCaptionsInAnyLanguage() {
        // No captions at all is not a dead end -- TranscriptionService reads empty segments as
        // "fall back to real Speech-to-Text" (see WhisperTranscriptionProvider), so this must
        // resolve successfully, not throw.
        when(processRunner.run(any(), any())).thenReturn(new ProcessResult(0, """
                {"id": "silent123", "title": "No captions anywhere", "duration": 60, "availability": "public",
                 "is_live": false, "language": null, "subtitles": {}, "automatic_captions": {}}
                """, ""));

        SourceResolution resolution = sourceProvider.resolve(new SourceRequest("https://www.youtube.com/watch?v=silent123"));

        assertThat(resolution.segments()).isEmpty();
        assertThat(resolution.video().id()).isEqualTo("silent123");
        assertThat(resolution.source()).isEqualTo(TranscriptSource.SPEECH_TO_TEXT);
    }

    private static final String SAMPLE_JSON3 = """
            {
              "events": [
                { "segs": [ { "utf8": "" } ] },
                { "tStartMs": 1360, "dDurationMs": 1680, "segs": [ { "utf8": "[music]" } ] },
                { "tStartMs": 18640, "dDurationMs": 3240, "segs": [ { "utf8": "We're no strangers to love" } ] }
              ]
            }
            """;

    @Test
    void parsesSegmentsFromTheSelectedSubtitleFile() {
        CaptionTrack track = new CaptionTrack("en", true);
        writeSubtitleFileFor(track);

        List<TranscriptSegment> segments = sourceProvider.fetchSegments(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ", track);

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0)).isEqualTo(new TranscriptSegment(0, 1360, 3040, "[music]"));
        assertThat(segments.get(1)).isEqualTo(new TranscriptSegment(1, 18640, 21880, "We're no strangers to love"));
    }

    @Test
    void trimsACueThatStaysOnScreenPastTheStartOfTheNextOne() {
        // Rolling auto-captions leave a line up while the next appears, so a cue's declared
        // duration overruns the following cue. Taken verbatim it stretches the merged line's
        // duration and drags anything paced by it behind the audio.
        CaptionTrack track = new CaptionTrack("en", false);
        writeSubtitleFile(track, """
                {
                  "events": [
                    { "tStartMs": 52600, "dDurationMs": 14070, "segs": [ { "utf8": "I tried so hard" } ] },
                    { "tStartMs": 61300, "dDurationMs": 11700, "segs": [ { "utf8": "and got so far" } ] }
                  ]
                }
                """);

        List<TranscriptSegment> segments = sourceProvider.fetchSegments(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ", track);

        assertThat(segments.get(0)).isEqualTo(new TranscriptSegment(0, 52600, 61300, "I tried so hard"));
        // The last cue has nothing to be trimmed against, so it keeps its declared end.
        assertThat(segments.get(1)).isEqualTo(new TranscriptSegment(1, 61300, 73000, "and got so far"));
    }

    @Test
    void leavesACueAloneWhenItAlreadyEndsBeforeTheNextBegins() {
        CaptionTrack track = new CaptionTrack("en", true);
        writeSubtitleFile(track, """
                {
                  "events": [
                    { "tStartMs": 1000, "dDurationMs": 500, "segs": [ { "utf8": "uno" } ] },
                    { "tStartMs": 9000, "dDurationMs": 500, "segs": [ { "utf8": "dos" } ] }
                  ]
                }
                """);

        List<TranscriptSegment> segments = sourceProvider.fetchSegments(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ", track);

        assertThat(segments.get(0).endMs()).isEqualTo(1500);
    }

    @Test
    void rejectsWhenTheSelectedTrackTurnsOutNotToExistAfterAll() {
        when(processRunner.run(any(), any())).thenReturn(new ProcessResult(0, "", ""));

        assertThatThrownBy(() -> sourceProvider.fetchSegments(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "dQw4w9WgXcQ", new CaptionTrack("en", true)))
                .isInstanceOf(UnsupportedSourceException.class);
    }

    @Test
    void treatsANonZeroExitCodeFetchingTheSelectedTrackAsProviderUnavailable() {
        // Reproduces a real case: the metadata call said this track should exist, but the actual
        // download hit a transient failure (e.g. YouTube responding 429 Too Many Requests). That
        // must NOT be reported as "no captions available" (not retryable) -- it's a transient
        // provider problem (retryable).
        when(processRunner.run(any(), any()))
                .thenReturn(new ProcessResult(1, "", "ERROR: HTTP Error 429: Too Many Requests"));

        assertThatThrownBy(() -> sourceProvider.fetchSegments(
                "https://www.youtube.com/watch?v=fLexgOxsZu0", "fLexgOxsZu0", new CaptionTrack("en", false)))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    private void writeSubtitleFileFor(CaptionTrack track) {
        writeSubtitleFile(track, SAMPLE_JSON3);
    }

    private void writeSubtitleFile(CaptionTrack track, String json3) {
        when(processRunner.run(any(), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            String expectedFlag = track.manual() ? "--write-subs" : "--write-auto-subs";
            if (command.contains(expectedFlag)) {
                Path dir = Path.of(command.get(command.indexOf("-P") + 1));
                Files.writeString(dir.resolve("dQw4w9WgXcQ." + track.language() + ".json3"), json3);
            }
            return new ProcessResult(0, "", "");
        });
    }
}
