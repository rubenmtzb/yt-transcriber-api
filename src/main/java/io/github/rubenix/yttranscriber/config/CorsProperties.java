package io.github.rubenix.yttranscriber.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(String[] allowedOrigins) {
}
