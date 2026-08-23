package io.github.rubenix.yttranscriber.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TranscriptionRequestDto(

        @NotBlank(message = "youtubeUrl must not be blank")
        @Pattern(
                regexp = "^https?://(www\\.|m\\.|music\\.)?(youtube\\.com/|youtu\\.be/).+$",
                message = "youtubeUrl must be a valid YouTube video URL")
        String youtubeUrl,

        @NotBlank(message = "targetLanguage must not be blank")
        @Pattern(regexp = "^[a-z]{2}$", message = "targetLanguage must be an ISO 639-1 two-letter code")
        String targetLanguage) {
}
