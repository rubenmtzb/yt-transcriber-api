package io.github.rubenix.yttranscriber.integration.transcription;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.ObjectMapper;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionOutcome;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionProvider;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionRequest;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import io.github.rubenix.yttranscriber.exception.UnsupportedSourceException;
import io.github.rubenix.yttranscriber.integration.process.ExternalProcessRunner;
import io.github.rubenix.yttranscriber.integration.youtube.YtDlpProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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

        Path tempDir = createTempDirectory();
        try {
            Path audioFile = extractAudio(request.youtubeUrl(), request.video().id(), tempDir);
            WhisperOutput output = runWhisper(audioFile, tempDir, request.sourceLanguage());
            List<TranscriptSegment> segments = toSegments(output);
            if (segments.isEmpty()) {
                throw new UnsupportedSourceException("Could not detect any speech in this video's audio.");
            }
            return new TranscriptionOutcome(output.language(), segments);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private Path extractAudio(String youtubeUrl, String videoId, Path tempDir) {
        List<String> command = List.of(
                ytDlpProperties.binaryPath(),
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
                "-oj",
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
            throw new ProviderUnavailableException("Could not parse the transcription engine's output.");
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
            segments.add(new TranscriptSegment(sequence++, startMs, endMs, text));
        }
        return segments;
    }

    private Path createTempDirectory() {
        try {
            return Files.createTempDirectory("whisper-stt-");
        } catch (IOException e) {
            throw new ProviderUnavailableException("Could not create a temporary directory for transcription.");
        }
    }

    private void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort cleanup; the OS will reclaim the temp dir eventually regardless
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException ignored) {
            // best-effort cleanup of an ephemeral temp file
        }
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WhisperSegment(WhisperOffsets offsets, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WhisperOffsets(Long from, Long to) {
    }
}
