package io.github.rubenix.yttranscriber.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * A {@code List} rather than an array: records derive equals/hashCode/toString from their
 * components by identity for arrays, so an array component would make this type compare and print
 * uselessly, and would hand every caller a mutable reference to the configured origins.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
