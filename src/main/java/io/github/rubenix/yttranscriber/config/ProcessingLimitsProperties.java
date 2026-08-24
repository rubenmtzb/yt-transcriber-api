package io.github.rubenix.yttranscriber.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.processing")
public record ProcessingLimitsProperties(
        @Min(1) long maxVideoDurationSeconds,
        @Min(1) int maxRequestsPerHour,
        @Min(1) long maxAudioMinutesPerHour,
        @Min(1) int maxConcurrentTranscriptions) {
}
