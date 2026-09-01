package io.github.rubenix.yttranscriber.api;

import io.github.rubenix.yttranscriber.application.ProcessingStage;
import io.github.rubenix.yttranscriber.application.ProgressListener;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSource;
import io.github.rubenix.yttranscriber.application.TranscriptionResult;
import io.github.rubenix.yttranscriber.application.TranscriptionService;
import io.github.rubenix.yttranscriber.limiter.UsageLimiter;
import io.github.rubenix.yttranscriber.limiter.UsageSnapshot;
import io.github.rubenix.yttranscriber.domain.source.VideoMetadata;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Exercises the real embedded server (not a MockMvc slice), same reasoning as CorsConfigTest:
 * this is a real streaming HTTP response, and mocking TranscriptionService keeps it fast and
 * deterministic without touching yt-dlp/whisper-cli/DeepL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TranscriptionStreamTest {

    @LocalServerPort
    private int port;

    @MockitoBean
    private TranscriptionService transcriptionService;

    @Test
    void streamsRealProgressStagesThenTheResult() {
        when(transcriptionService.process(eq("https://youtu.be/abc123"), eq("es"), any(), any()))
                .thenAnswer(invocation -> {
                    ProgressListener listener = invocation.getArgument(3);
                    listener.onStage(ProcessingStage.RESOLVING_VIDEO);
                    listener.onStage(ProcessingStage.TRANSLATING);
                    listener.onStage(ProcessingStage.PREPARING_RESULT);
                    return new TranscriptionResult(
                            new VideoMetadata("abc123", "Title", 90), "en", "es", TranscriptSource.MANUAL_CAPTIONS, List.of());
                });

        String body = get("https://youtu.be/abc123", "es");

        assertThat(body).contains("event:session");
        assertThat(body).contains("event:stage");
        // Exact, unquoted match on purpose: SseEmitter JSON-serializes non-String .data(...)
        // arguments, which would silently wrap a bare enum in quotes ("RESOLVING_VIDEO") -- a
        // real bug caught via a raw curl trace, since the frontend compares this value against
        // plain ProcessingStage string literals and a quoted mismatch fails silently, not loudly.
        assertThat(body).contains("data:RESOLVING_VIDEO");
        assertThat(body).contains("data:TRANSLATING");
        assertThat(body).contains("data:PREPARING_RESULT");
        assertThat(body).contains("event:result");
        assertThat(body).contains("\"id\":\"abc123\"");
    }

    @Test
    void streamsAnErrorEventWhenTheServiceThrowsAnApplicationException() {
        when(transcriptionService.process(eq("https://youtu.be/abc123"), eq("es"), any(), any()))
                .thenThrow(new ProviderUnavailableException("boom"));

        String body = get("https://youtu.be/abc123", "es");

        assertThat(body).contains("event:error");
        assertThat(body).contains("PROVIDER_UNAVAILABLE");
        assertThat(body).doesNotContain("event:result");
    }

    @Test
    void rejectsAMalformedYoutubeUrlWithAnOrdinaryBadRequestInsteadOfOpeningTheStream() {
        assertThatThrownBy(() -> get("not-a-url", "es"))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
    }

    private String get(String youtubeUrl, String targetLanguage) {
        // RestClient.uri(String) treats its argument as a template and re-encodes it, so a
        // pre-encoded query string passed that way gets double-encoded. Building a real URI
        // object instead and passing that avoids any further encoding.
        String query = "youtubeUrl=%s&targetLanguage=%s".formatted(
                URLEncoder.encode(youtubeUrl, StandardCharsets.UTF_8),
                URLEncoder.encode(targetLanguage, StandardCharsets.UTF_8));
        URI uri = URI.create("http://localhost:%d/api/v1/transcriptions/stream?%s".formatted(port, query));
        return RestClient.create().get()
                .uri(uri)
                .retrieve()
                .body(String.class);
    }
}
