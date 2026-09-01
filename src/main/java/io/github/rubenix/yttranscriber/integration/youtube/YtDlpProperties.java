package io.github.rubenix.yttranscriber.integration.youtube;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.ytdlp")
public record YtDlpProperties(
        @NotBlank String binaryPath,
        @Min(1) long timeoutSeconds) {
}
