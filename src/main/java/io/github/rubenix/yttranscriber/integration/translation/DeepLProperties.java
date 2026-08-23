package io.github.rubenix.yttranscriber.integration.translation;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.deepl")
public record DeepLProperties(String apiKey) {
}
