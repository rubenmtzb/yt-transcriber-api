package io.github.rubenix.yttranscriber.integration;

import io.github.rubenix.yttranscriber.domain.transcription.TimedWord;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.integration.process.ExternalProcessRunner;
import io.github.rubenix.yttranscriber.integration.process.ExternalProcessRunner.ProcessResult;
import io.github.rubenix.yttranscriber.integration.transcription.WhisperProperties;
import io.github.rubenix.yttranscriber.integration.transcription.WhisperTranscriptionProvider;
import io.github.rubenix.yttranscriber.integration.youtube.YtDlpProperties;
import io.github.rubenix.yttranscriber.integration.youtube.YtDlpSourceProvider;
import io.github.rubenix.yttranscriber.domain.source.SourceRequest;
import io.github.rubenix.yttranscriber.domain.source.SourceResolution;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionOutcome;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionRequest;
import io.github.rubenix.yttranscriber.domain.source.VideoMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Runs both parsers over output captured verbatim from the real tools, rather than over examples
 * written to match what the parser already does. Hand-written samples agree with whatever the code
 * assumes; these files are the only check that the assumptions match the tools.
 *
 * Sources: yt-dlp auto-captions for `8qLL2Gx3I_k`, and whisper.cpp `-ojf` for `maEVfX9zRIE`.
 */
@ExtendWith(MockitoExtension.class)
class RealFixtureWordTimingTest {

    @Mock
    private ExternalProcessRunner processRunner;

    private String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/fixtures", name));
    }

    @Test
    void readsYoutubesPerWordOffsetsOutOfARealCaptionFile() throws Exception {
        when(processRunner.run(any(), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.contains("--print")) {
                return new ProcessResult(0, """
                        {"id": "8qLL2Gx3I_k", "title": "In The End", "duration": 235, "availability": "public",
                         "is_live": false, "language": "en", "subtitles": {}, "automatic_captions": {"en-orig": []}}
                        """, "");
            }
            Path dir = Path.of(command.get(command.indexOf("-P") + 1));
            Files.writeString(dir.resolve("8qLL2Gx3I_k.en.json3"), fixture("youtube-auto-captions.json3"));
            return new ProcessResult(0, "", "");
        });
        var provider = new YtDlpSourceProvider(processRunner, new ObjectMapper(), new YtDlpProperties("yt-dlp", 30));

        SourceResolution resolution = provider.resolve(new SourceRequest("https://youtu.be/8qLL2Gx3I_k"));

        TranscriptSegment lyric = resolution.segments().stream()
                .filter(segment -> segment.text().startsWith("I tried so hard"))
                .findFirst()
                .orElseThrow();

        // The cue starts at 52600 and "tried" carries tOffsetMs=2720, so it is sung at 55320 --
        // a full 2.7s after the line begins, which no character-weighted estimate would find.
        assertThat(lyric.words()).isNotEmpty();
        assertThat(lyric.words().get(0).text().trim()).isEqualTo("I");
        assertThat(lyric.words().get(1).text().trim()).isEqualTo("tried");
        assertThat(lyric.words().get(1).startMs()).isEqualTo(55_320);
        assertThat(lyric.words()).allSatisfy(word -> assertThat(word.endMs()).isGreaterThanOrEqualTo(word.startMs()));
    }

    @Test
    void keepsEveryWordInsideTheLineItBelongsTo() throws Exception {
        when(processRunner.run(any(), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.contains("--print")) {
                return new ProcessResult(0, """
                        {"id": "8qLL2Gx3I_k", "title": "In The End", "duration": 235, "availability": "public",
                         "is_live": false, "language": "en", "subtitles": {}, "automatic_captions": {"en-orig": []}}
                        """, "");
            }
            Path dir = Path.of(command.get(command.indexOf("-P") + 1));
            Files.writeString(dir.resolve("8qLL2Gx3I_k.en.json3"), fixture("youtube-auto-captions.json3"));
            return new ProcessResult(0, "", "");
        });
        var provider = new YtDlpSourceProvider(processRunner, new ObjectMapper(), new YtDlpProperties("yt-dlp", 30));

        SourceResolution resolution = provider.resolve(new SourceRequest("https://youtu.be/8qLL2Gx3I_k"));

        // Cues overrun each other in this file, and trimming them must take their words along.
        assertThat(resolution.segments()).allSatisfy(segment ->
                assertThat(segment.words()).allSatisfy(word -> {
                    assertThat(word.startMs()).isGreaterThanOrEqualTo(segment.startMs());
                    assertThat(word.endMs()).isLessThanOrEqualTo(segment.endMs());
                }));
    }

    @Test
    void rebuildsWholeWordsFromRealWhisperTokens() throws Exception {
        when(processRunner.run(any(), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.contains("--extract-audio")) {
                Path dir = Path.of(command.get(command.indexOf("-P") + 1));
                Files.writeString(dir.resolve("maEVfX9zRIE.wav"), "");
                return new ProcessResult(0, "", "");
            }
            Path out = Path.of(command.get(command.indexOf("-of") + 1) + ".json");
            Files.writeString(out, fixture("whisper-full.json"));
            return new ProcessResult(0, "", "");
        });
        var provider = new WhisperTranscriptionProvider(processRunner, new ObjectMapper(),
                new YtDlpProperties("yt-dlp", 30), new WhisperProperties("whisper-cli", "model.bin", 900, 15));

        TranscriptionOutcome outcome = provider.transcribe(new TranscriptionRequest(
                "https://youtu.be/maEVfX9zRIE", new VideoMetadata("maEVfX9zRIE", "Un beso y una flor", 223), "es"));

        TranscriptSegment lyric = outcome.segments().stream()
                .filter(segment -> segment.text().contains("Dejaré"))
                .findFirst()
                .orElseThrow();
        List<String> words = lyric.words().stream().map(word -> word.text().trim()).toList();

        // "Dejaré" arrives as three tokens (" De", "jar", "é") and must come back out as one word.
        assertThat(words).contains("Dejaré", "tierra", "campos");
        TimedWord dejare = lyric.words().stream().filter(w -> w.text().trim().equals("Dejaré")).findFirst().orElseThrow();
        assertThat(dejare.startMs()).isEqualTo(12_450);
        // Punctuation has no leading space, so it joins the word it follows rather than standing alone.
        assertThat(words).contains("ti,");
    }
}
