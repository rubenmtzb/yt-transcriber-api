package io.github.rubenix.yttranscriber.integration.translation;

import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.translation.TranslatedSegment;
import io.github.rubenix.yttranscriber.domain.translation.TranslationRequest;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import io.github.rubenix.yttranscriber.exception.RateLimitedException;
import io.github.rubenix.yttranscriber.exception.TranslationQuotaExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DeepLTranslationProviderTest {

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
}
