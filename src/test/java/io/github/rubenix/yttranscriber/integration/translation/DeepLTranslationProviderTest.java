package io.github.rubenix.yttranscriber.integration.translation;

import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.translation.TranslatedSegment;
import io.github.rubenix.yttranscriber.domain.translation.TranslationRequest;
import io.github.rubenix.yttranscriber.domain.transcription.TimedWord;
import io.github.rubenix.yttranscriber.application.ProcessingBudget;
import io.github.rubenix.yttranscriber.exception.ProcessingTimeoutException;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import io.github.rubenix.yttranscriber.exception.RateLimitedException;
import io.github.rubenix.yttranscriber.exception.TranslationQuotaExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Collectors;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DeepLTranslationProviderTest {

    @ParameterizedTest
    @ValueSource(ints = {50, 51, 101})
    void batchesAtFiftyTextsAndPreservesEverySegmentAndWord(int count) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (int start = 0; start < count; start += 50) {
            int size = Math.min(50, count - start);
            server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
                    .andExpect(request -> {
                        String body = ((MockClientHttpRequest) request).getBodyAsString();
                        assertThat(Arrays.stream(body.split("&")).filter(part -> part.startsWith("text=")).count())
                                .isEqualTo(size);
                        assertThat(body).contains("target_lang=ES");
                    })
                    .andRespond(withSuccess(response(size), MediaType.APPLICATION_JSON));
        }
        var segments = IntStream.range(0, count)
                .mapToObj(i -> new TranscriptSegment(i * 2, i * 1000, i * 1000 + 500, "Hello " + i,
                        List.of(new TimedWord("Hello", i * 1000, i * 1000 + 500))))
                .toList();
        var provider = new DeepLTranslationProvider(builder, new DeepLProperties("test-key:fx"));

        var result = provider.translate(new TranslationRequest(segments, "es"));

        assertThat(result).hasSize(count);
        for (int i = 0; i < count; i++) {
            var source = segments.get(i);
            assertThat(result.get(i)).isEqualTo(new TranslatedSegment(source.sequence(), source.startMs(),
                    source.endMs(), source.text(), "Hola", source.words()));
        }
        server.verify();
    }

    @Test
    void splitsOnEncodedUtf8BytesRatherThanCharacterCount() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
                    .andExpect(request -> {
                        String body = ((MockClientHttpRequest) request).getBodyAsString();
                        assertThat(body.length()).isLessThanOrEqualTo(128 * 1024);
                        assertThat(body).contains("%F0%9F%98%80").contains("%26%2B");
                    })
                    .andRespond(withSuccess(response(1), MediaType.APPLICATION_JSON));
        }
        String text = "😀&+".repeat(4000);
        var provider = new DeepLTranslationProvider(builder, new DeepLProperties("test-key:fx"));
        var result = provider.translate(new TranslationRequest(List.of(
                new TranscriptSegment(0, 0, 1000, text),
                new TranscriptSegment(1, 1000, 2000, text)), "es"));
        assertThat(result).hasSize(2);
        server.verify();
    }

    @Test
    void acceptsExactlyTheBodyLimitAndSplitsTheNextText() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
                .andExpect(request -> assertThat(((MockClientHttpRequest) request).getBodyAsString().length())
                        .isEqualTo(128 * 1024))
                .andRespond(withSuccess(response(1), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
                .andRespond(withSuccess(response(1), MediaType.APPLICATION_JSON));
        var provider = new DeepLTranslationProvider(builder, new DeepLProperties("test-key:fx"));
        String text = "a".repeat(128 * 1024 - "target_lang=ES&text=".length());
        assertThat(provider.translate(new TranslationRequest(List.of(
                new TranscriptSegment(0, 0, 1000, text), new TranscriptSegment(1, 1000, 2000, "b")), "es")))
                .hasSize(2);
        server.verify();
    }

    @Test
    void rejectsAnOversizedSingleSegmentBeforeSpendingAnyProviderCalls() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var provider = new DeepLTranslationProvider(builder, new DeepLProperties("test-key:fx"));
        var segments = List.of(new TranscriptSegment(0, 0, 1000, "valid"),
                new TranscriptSegment(1, 1000, 2000, "😀".repeat(12000)));

        assertThatThrownBy(() -> provider.translate(new TranslationRequest(segments, "es")))
                .isInstanceOf(ProviderUnavailableException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"translations\":[]}", "{\"translations\":[null]}", "{\"translations\":[{}]}"})
    void rejectsInvalidResponsesFromALaterBatchWithoutReturningPartialResults(String response) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
                .andRespond(withSuccess(response(50), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        var provider = new DeepLTranslationProvider(builder, new DeepLProperties("test-key:fx"));

        assertThatThrownBy(() -> provider.translate(new TranslationRequest(segments(51), "es")))
                .isInstanceOf(ProviderUnavailableException.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 456})
    void stopsAtALaterBatchFailureWithoutRetryingOrCallingTheNextBatch(int status) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
                .andRespond(withSuccess(response(50), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status)));
        var provider = new DeepLTranslationProvider(builder, new DeepLProperties("test-key:fx"));

        assertThatThrownBy(() -> provider.translate(new TranslationRequest(segments(101), "es")))
                .isInstanceOf(status == 429 ? RateLimitedException.class : TranslationQuotaExceededException.class);
        server.verify();
    }

    @Test
    void doesNotStartAnotherBatchWhenTheGlobalBudgetIsSpent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api-free.deepl.com/v2/translate")).andRespond(request -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return withSuccess(response(50), MediaType.APPLICATION_JSON).createResponse(request);
        });
        var provider = new DeepLTranslationProvider(builder, new DeepLProperties("test-key:fx"));

        assertThatThrownBy(() -> ProcessingBudget.run(Duration.ofMillis(250),
                () -> provider.translate(new TranslationRequest(segments(51), "es"))))
                .isInstanceOf(ProcessingTimeoutException.class);
        server.verify();
    }

    private static String response(int count) {
        return "{\"translations\":[" + IntStream.range(0, count)
                .mapToObj(i -> "{\"text\":\"Hola\"}").collect(Collectors.joining(",")) + "]}";
    }

    private static List<TranscriptSegment> segments(int count) {
        return IntStream.range(0, count).mapToObj(i -> new TranscriptSegment(i, i * 1000, i * 1000 + 500, "Hi")).toList();
    }

    @Test
    void failsFastWhenNoApiKeyIsConfigured() {
        var provider = new DeepLTranslationProvider(RestClient.builder(), new DeepLProperties(""));
        var request = new TranslationRequest(List.of(new TranscriptSegment(0, 0, 1000, "hi")), "es");

        assertThatThrownBy(() -> provider.translate(request))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void returnsEmptyListWithoutCallingDeepLWhenThereAreNoSegments() {
        var provider = new DeepLTranslationProvider(RestClient.builder(), new DeepLProperties("dummy-key:fx"));
        var request = new TranslationRequest(List.of(), "es");

        assertThat(provider.translate(request)).isEmpty();
    }

    @Test
    void translatesSegmentsPreservingTimestampsAndOrder() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "DeepL-Auth-Key test-key:fx"))
                .andRespond(withSuccess("""
                        {"translations":[{"text":"Hola a todos"},{"text":"Cómo estás"}]}
                        """, MediaType.APPLICATION_JSON));

        var provider = new DeepLTranslationProvider(builder, new DeepLProperties("test-key:fx"));
        var segments = List.of(
                new TranscriptSegment(0, 0, 1000, "Hello everybody"),
                new TranscriptSegment(1, 1000, 2000, "How are you"));
        var request = new TranslationRequest(segments, "es");

        List<TranslatedSegment> result = provider.translate(request);

        assertThat(result).containsExactly(
                new TranslatedSegment(0, 0, 1000, "Hello everybody", "Hola a todos"),
                new TranslatedSegment(1, 1000, 2000, "How are you", "Cómo estás"));
        server.verify();
    }

    @Test
    void mapsDeepLPerMinuteRateLimitToRateLimitedException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
                .andRespond(withStatus(HttpStatusCode.valueOf(429)));

        var provider = new DeepLTranslationProvider(builder, new DeepLProperties("test-key:fx"));
        var request = new TranslationRequest(List.of(new TranscriptSegment(0, 0, 1000, "hi")), "es");

        assertThatThrownBy(() -> provider.translate(request))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void mapsDeepLMonthlyQuotaExhaustionToTranslationQuotaExceededException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
                .andRespond(withStatus(HttpStatusCode.valueOf(456)));

        var provider = new DeepLTranslationProvider(builder, new DeepLProperties("test-key:fx"));
        var request = new TranslationRequest(List.of(new TranscriptSegment(0, 0, 1000, "hi")), "es");

        assertThatThrownBy(() -> provider.translate(request))
                .isInstanceOf(TranslationQuotaExceededException.class);
    }

    @Test
    void keepsWhatDeepLActuallyAnsweredWhenTheCallIsRejected() {
        // 403 (a key that has been rotated or revoked), 400 and a DeepL outage all reach the caller
        // as the same vague 503. Without the cause the log says only "request failed", and an
        // expired key is indistinguishable from a network problem -- which is the state this was
        // found in, right before the key was due to be rotated.
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api-free.deepl.com/v2/translate"))
                .andRespond(withStatus(HttpStatusCode.valueOf(403)));

        var provider = new DeepLTranslationProvider(builder, new DeepLProperties("revoked-key:fx"));
        var request = new TranslationRequest(List.of(new TranscriptSegment(0, 0, 1000, "hi")), "es");

        assertThatThrownBy(() -> provider.translate(request))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasCauseInstanceOf(Exception.class)
                .cause().hasMessageContaining("403");
    }
}
