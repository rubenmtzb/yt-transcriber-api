package io.github.rubenix.yttranscriber.integration.transcription;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.rubenix.yttranscriber.domain.transcription.TimedWord;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionOutcome;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionProvider;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionRequest;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import io.github.rubenix.yttranscriber.exception.UnsupportedSourceException;
import io.github.rubenix.yttranscriber.integration.process.ExternalProcessRunner;
import io.github.rubenix.yttranscriber.integration.process.TempWorkspace;
import io.github.rubenix.yttranscriber.integration.youtube.YtDlpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * TranscriptionProvider backed by a local whisper.cpp install: extracts the video's audio with
 * yt-dlp, then transcribes it with whisper-cli. Runs entirely locally -- no external paid API, no
 * per-request cost -- deliberate, since this is an unfunded personal project (see the site's own
 * footer disclosure). Requires whisper-cli and a ggml model to be installed and configured; if
 * they're missing, calls fail with ProviderUnavailableException the same way DeepL does without an
 * API key, rather than the app refusing to start.
 */
@Component
public class WhisperTranscriptionProvider implements TranscriptionProvider {

    private static final Logger log = LoggerFactory.getLogger(WhisperTranscriptionProvider.class);
    // whisper.cpp's own markers -- [_BEG_], [_TT_600] and friends -- which are structure, not speech.
    private static final Pattern CONTROL_TOKEN = Pattern.compile("\\s*\\[_[A-Z_0-9]+]\\s*");

    private final ExternalProcessRunner processRunner;
    private final ObjectMapper objectMapper;
    private final YtDlpProperties ytDlpProperties;
    private final WhisperProperties whisperProperties;

    public WhisperTranscriptionProvider(ExternalProcessRunner processRunner, ObjectMapper objectMapper,
                                         YtDlpProperties ytDlpProperties, WhisperProperties whisperProperties) {
        this.processRunner = processRunner;
        this.objectMapper = objectMapper;
        this.ytDlpProperties = ytDlpProperties;
        this.whisperProperties = whisperProperties;
    }

    @Override
    public TranscriptionOutcome transcribe(TranscriptionRequest request) {
        if (whisperProperties.modelPath() == null || whisperProperties.modelPath().isBlank()) {
            throw new ProviderUnavailableException("No local transcription model is configured yet.");
        }
        if (request.video().durationSeconds() < whisperProperties.minAudioDurationSeconds()) {
            // Confirmed empirically: Whisper's language auto-detection is unreliable on clips
            // shorter than ~10s (misidentified a 6.5s English clip as Spanish and transcribed
            // gibberish). Below the configured floor, refuse rather than risk a wrong transcript.
            throw new UnsupportedSourceException(
                    "This video is too short to transcribe reliably (minimum %d seconds)."
                            .formatted(whisperProperties.minAudioDurationSeconds()));
        }

        try (TempWorkspace workspace = TempWorkspace.create(
                "whisper-stt-", "Could not create a temporary directory for transcription.")) {
            Path audioFile = extractAudio(request.youtubeUrl(), request.video().id(), workspace.directory());
            WhisperOutput output = runWhisper(audioFile, workspace.directory(), request.sourceLanguage());
            List<TranscriptSegment> segments = toSegments(output);
            if (segments.isEmpty()) {
                throw new UnsupportedSourceException("Could not detect any speech in this video's audio.");
            }
            return new TranscriptionOutcome(output.language(), segments);
        }
    }

    private Path extractAudio(String youtubeUrl, String videoId, Path tempDir) {
        List<String> command = List.of(
                ytDlpProperties.binaryPath(),
                // Same reasoning as YtDlpSourceProvider's own calls: without these, a playlist or
                // channel URL makes yt-dlp download audio for every entry it finds.
                "--no-playlist", "--playlist-items", "1",
                "--extract-audio",
                "--audio-format", "wav",
                "--postprocessor-args", "ffmpeg:-ar 16000 -ac 1",
                "-P", tempDir.toString(),
                "-o", "%(id)s.%(ext)s",
                youtubeUrl);

        var result = processRunner.run(command, Duration.ofSeconds(whisperProperties.timeoutSeconds()));
        Path expected = tempDir.resolve(videoId + ".wav");

        if (result.exitCode() != 0 || !Files.exists(expected)) {
            log.warn("Could not extract audio for {}: exit={}, stderr={}", videoId, result.exitCode(), result.stderr());
            throw new ProviderUnavailableException("Could not download this video's audio for transcription.");
        }
        return expected;
    }

    private WhisperOutput runWhisper(Path audioFile, Path tempDir, String languageHint) {
        Path outputBase = tempDir.resolve("transcript");
        List<String> command = List.of(
                whisperProperties.binaryPath(),
                "-m", whisperProperties.modelPath(),
                "-f", audioFile.toString(),
                "-l", languageHint != null && !languageHint.isBlank() ? languageHint : "auto",
                "-ojf",
                "-of", outputBase.toString(),
                "-np");

        var result = processRunner.run(command, Duration.ofSeconds(whisperProperties.timeoutSeconds()));
        Path outputFile = tempDir.resolve("transcript.json");

        if (result.exitCode() != 0 || !Files.exists(outputFile)) {
            log.warn("whisper-cli failed: exit={}, stderr={}", result.exitCode(), result.stderr());
            throw new ProviderUnavailableException("The local transcription engine failed to process this video.");
        }

        try {
            return objectMapper.readValue(Files.readString(outputFile), WhisperOutput.class);
        } catch (Exception e) {
            throw new ProviderUnavailableException("Could not parse the transcription engine's output.", e);
        }
    }

    private List<TranscriptSegment> toSegments(WhisperOutput output) {
        List<TranscriptSegment> segments = new ArrayList<>();
        List<WhisperSegment> raw = output.transcription() != null ? output.transcription() : List.of();
        int sequence = 0;
        for (WhisperSegment segment : raw) {
            String text = segment.text() != null ? segment.text().trim() : "";
            if (text.isEmpty() || segment.offsets() == null) {
                continue;
            }
            long startMs = segment.offsets().from() != null ? segment.offsets().from() : 0L;
            long endMs = segment.offsets().to() != null ? segment.offsets().to() : startMs;
            segments.add(new TranscriptSegment(sequence++, startMs, endMs, text, wordsOf(segment)));
        }
        return segments;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WhisperOutput(WhisperResult result, List<WhisperSegment> transcription) {
        String language() {
            return result != null ? result.language() : null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WhisperResult(String language) {
    }

    /**
     * Rebuilds whole words out of whisper.cpp's tokens.
     *
     * The model emits sub-word pieces -- "Deja" then "ré" -- each with its own offsets, so a token
     * is not a word. A leading space is what marks the start of a new one, which is why pieces are
     * appended rather than joined. Control tokens like {@code [_BEG_]} and the timestamp markers
     * carry no text and are dropped.
     */
    private List<TimedWord> wordsOf(WhisperSegment segment) {
        if (segment.tokens() == null) {
            return List.of();
        }

        List<TimedWord> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        long currentStartMs = 0;
        long currentEndMs = 0;

        for (WhisperToken token : segment.tokens()) {
            String piece = token.text();
            if (piece == null || token.offsets() == null || CONTROL_TOKEN.matcher(piece).matches()) {
                continue;
            }
            long from = token.offsets().from() != null ? token.offsets().from() : 0L;
            long to = token.offsets().to() != null ? token.offsets().to() : from;

            boolean startsNewWord = piece.startsWith(" ") || current.isEmpty();
            if (startsNewWord && !current.isEmpty()) {
                words.add(new TimedWord(current.toString(), currentStartMs, currentEndMs));
                current.setLength(0);
            }
            if (current.isEmpty()) {
                currentStartMs = from;
            }
            current.append(piece);
            currentEndMs = Math.max(currentEndMs, to);
        }
        if (!current.isEmpty()) {
            words.add(new TimedWord(current.toString(), currentStartMs, currentEndMs));
        }
        return words.size() < 2 ? List.of() : words;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WhisperSegment(WhisperOffsets offsets, String text, List<WhisperToken> tokens) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WhisperToken(String text, WhisperOffsets offsets) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WhisperOffsets(Long from, Long to) {
    }
}
