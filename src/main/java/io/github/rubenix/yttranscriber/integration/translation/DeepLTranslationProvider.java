package io.github.rubenix.yttranscriber.integration.translation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.translation.TranslatedSegment;
import io.github.rubenix.yttranscriber.domain.translation.TranslationProvider;
import io.github.rubenix.yttranscriber.domain.translation.TranslationRequest;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import io.github.rubenix.yttranscriber.exception.RateLimitedException;
import io.github.rubenix.yttranscriber.exception.TranslationQuotaExceededException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * TranslationProvider backed by the DeepL API. Requires app.deepl.api-key (TRANSLATION_API_KEY)
 * to be set; without it, every call fails fast with PROVIDER_UNAVAILABLE instead of attempting
 * a request that would only fail at DeepL's end anyway.
 */
@Component
public class DeepLTranslationProvider implements TranslationProvider {

    private final RestClient restClient;
    private final String apiKey;

    public DeepLTranslationProvider(RestClient.Builder restClientBuilder, DeepLProperties properties) {
        this.apiKey = properties.apiKey();
        String baseUrl = this.apiKey != null && this.apiKey.endsWith(":fx")
                ? "https://api-free.deepl.com/v2"
                : "https://api.deepl.com/v2";
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "DeepL-Auth-Key " + this.apiKey)
                .build();
    }

    @Override
    public List<TranslatedSegment> translate(TranslationRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ProviderUnavailableException("No translation provider is configured yet.");
        }
        if (request.segments().isEmpty()) {
            return List.of();
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        request.segments().forEach(segment -> form.add("text", segment.text()));
        form.add("target_lang", request.targetLanguage().toUpperCase(Locale.ROOT));

        DeepLResponse response = callDeepL(form);

        if (response == null || response.translations() == null
                || response.translations().size() != request.segments().size()) {
            throw new ProviderUnavailableException("Unexpected response from the translation provider.");
        }

        return zip(request.segments(), response.translations());
    }

    private DeepLResponse callDeepL(MultiValueMap<String, String> form) {
        try {
            return restClient.post()
                    .uri("/translate")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(DeepLResponse.class);
        } catch (RestClientResponseException e) {
            // DeepL uses 429 for per-minute rate limiting (clears in moments, worth a short
            // retry) and 456 for the monthly character quota being exhausted (a free-tier demo
            // limit that only resets next month, not something retrying now will fix) -- these
            // need distinct handling so the UI doesn't invite the user to just "try again".
            int status = e.getStatusCode().value();
            if (status == 429) {
                throw new RateLimitedException("The translation provider's usage limit has been reached.");
            }
            if (status == 456) {
                throw new TranslationQuotaExceededException(
                        "This demo's monthly translation quota has been used up.");
            }
            throw new ProviderUnavailableException("DeepL translation request failed.");
        } catch (RestClientException e) {
            throw new ProviderUnavailableException("DeepL translation request failed.");
        }
    }

    private List<TranslatedSegment> zip(List<TranscriptSegment> segments, List<DeepLTranslation> translations) {
        return IntStream.range(0, segments.size())
                .mapToObj(i -> {
                    TranscriptSegment segment = segments.get(i);
                    String translatedText = translations.get(i).text();
                    return new TranslatedSegment(segment.sequence(), segment.startMs(), segment.endMs(),
                            segment.text(), translatedText, segment.words());
                })
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DeepLResponse(List<DeepLTranslation> translations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DeepLTranslation(String text) {
    }
}
