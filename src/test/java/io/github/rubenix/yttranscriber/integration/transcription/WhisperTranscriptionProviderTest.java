package io.github.rubenix.yttranscriber.integration.transcription;

import tools.jackson.databind.ObjectMapper;
import io.github.rubenix.yttranscriber.domain.source.VideoMetadata;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionOutcome;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionRequest;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import io.github.rubenix.yttranscriber.exception.UnsupportedSourceException;
import io.github.rubenix.yttranscriber.integration.process.ExternalProcessRunner;
import io.github.rubenix.yttranscriber.integration.process.ExternalProcessRunner.ProcessResult;
import io.github.rubenix.yttranscriber.integration.youtube.YtDlpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhisperTranscriptionProviderTest {

    private static final YtDlpProperties YTDLP_PROPERTIES = new YtDlpProperties("yt-dlp", 45);
    private static final WhisperProperties CONFIGURED = new WhisperProperties("whisper-cli", "/models/ggml-base.bin", 60);
    private static final WhisperProperties UNCONFIGURED = new WhisperProperties("whisper-cli", "", 60);

    @Mock
    private ExternalProcessRunner processRunner;

    private WhisperTranscriptionProvider provider;

    @BeforeEach
    void setUp() {
        provider = new WhisperTranscriptionProvider(processRunner, new ObjectMapper(), YTDLP_PROPERTIES, CONFIGURED);
    }

    private TranscriptionRequest request(String sourceLanguageHint) {
        return new TranscriptionRequest(
                "https://www.youtube.com/watch?v=abc123", new VideoMetadata("abc123", "Title", 90), sourceLanguageHint);
    }

    @Test
    void failsFastWithoutCallingAnythingWhenNoModelIsConfigured() {
        WhisperTranscriptionProvider unconfigured =
                new WhisperTranscriptionProvider(processRunner, new ObjectMapper(), YTDLP_PROPERTIES, UNCONFIGURED);

        assertThatThrownBy(() -> unconfigured.transcribe(request(null)))
                .isInstanceOf(ProviderUnavailableException.class);
        verify(processRunner, never()).run(any(), any());
    }

    @Test
    void extractsAudioAndTranscribesItSuccessfully() {
        stubSuccessfulPipeline("en", """
                {"result": {"language": "en"}, "transcription": [
                    {"offsets": {"from": 0, "to": 2000}, "text": " Hello everybody"},
                    {"offsets": {"from": 2000, "to": 4000}, "text": " Welcome back"}
                ]}
                """);

        TranscriptionOutcome outcome = provider.transcribe(request("en"));

        assertThat(outcome.language()).isEqualTo("en");
        assertThat(outcome.segments()).containsExactly(
                new TranscriptSegment(0, 0, 2000, "Hello everybody"),
                new TranscriptSegment(1, 2000, 4000, "Welcome back"));
    }

    @Test
    void passesTheLanguageHintThroughToWhisperCli() {
        stubSuccessfulPipeline("ru", """
                {"result": {"language": "ru"}, "transcription": [{"offsets": {"from": 0, "to": 1000}, "text": "Привет"}]}
                """);

        provider.transcribe(request("ru"));

        verify(processRunner).run(argThatContains("-l", "ru"), any());
    }

    @Test
    void fallsBackToAutoWhenThereIsNoLanguageHint() {
        stubSuccessfulPipeline(null, """
                {"result": {"language": "en"}, "transcription": [{"offsets": {"from": 0, "to": 1000}, "text": "Hello"}]}
                """);

        provider.transcribe(request(null));

        verify(processRunner).run(argThatContains("-l", "auto"), any());
    }

    @Test
    void treatsAFailedAudioExtractionAsProviderUnavailableWithoutRunningWhisper() {
        when(processRunner.run(any(), any())).thenReturn(new ProcessResult(1, "", "yt-dlp exploded"));

        assertThatThrownBy(() -> provider.transcribe(request(null)))
                .isInstanceOf(ProviderUnavailableException.class);
        verify(processRunner, times(1)).run(any(), any());
    }

    @Test
    void treatsAFailedWhisperRunAsProviderUnavailable() {
        when(processRunner.run(any(), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.contains("--extract-audio")) {
                writeAudioFile(command);
                return new ProcessResult(0, "", "");
            }
            return new ProcessResult(1, "", "whisper-cli exploded");
        });

        assertThatThrownBy(() -> provider.transcribe(request(null)))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void treatsNoDetectedSpeechAsUnsupportedSource() {
        stubSuccessfulPipeline(null, """
                {"result": {"language": "en"}, "transcription": []}
                """);

        assertThatThrownBy(() -> provider.transcribe(request(null)))
                .isInstanceOf(UnsupportedSourceException.class);
    }

    private void stubSuccessfulPipeline(String languageHint, String whisperJson) {
        when(processRunner.run(any(), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.contains("--extract-audio")) {
                writeAudioFile(command);
                return new ProcessResult(0, "", "");
            }
            Path outputBase = Path.of(command.get(command.indexOf("-of") + 1));
            Path outputJson = Path.of(outputBase.toString() + ".json");
            Files.writeString(outputJson, whisperJson);
            return new ProcessResult(0, "", "");
        });
    }

    private void writeAudioFile(List<String> command) throws Exception {
        Path dir = Path.of(command.get(command.indexOf("-P") + 1));
        Files.writeString(dir.resolve("abc123.wav"), "not real audio, just needs to exist");
    }

    private List<String> argThatContains(String flag, String value) {
        return org.mockito.ArgumentMatchers.argThat(command -> {
            int index = command.indexOf(flag);
            return index >= 0 && index + 1 < command.size() && command.get(index + 1).equals(value);
        });
    }
}
