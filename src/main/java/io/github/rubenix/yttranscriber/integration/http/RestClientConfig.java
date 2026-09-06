package io.github.rubenix.yttranscriber.integration.http;

import io.github.rubenix.yttranscriber.application.ProcessingBudget;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * RestClient.Builder factory for outbound provider adapters, with bounded timeouts so no
 * external call can hang indefinitely. Prototype-scoped: each adapter gets its own builder
 * instance to customize (baseUrl, headers) without interfering with any other adapter's.
 */
@Configuration
public class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        return RestClient.builder().requestFactory((uri, method) -> {
            // Factories are per-call: mutating a shared timeout would mix concurrent budgets.
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(ProcessingBudget.cap(READ_TIMEOUT));
            return requestFactory.createRequest(uri, method);
        });
    }
}
