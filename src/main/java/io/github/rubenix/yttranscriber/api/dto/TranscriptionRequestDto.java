package io.github.rubenix.yttranscriber.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TranscriptionRequestDto(

        @NotBlank(message = "youtubeUrl must not be blank")
        @Pattern(
                regexp = TranscriptionRequestDto.YOUTUBE_URL_PATTERN,
                message = "youtubeUrl must be a valid YouTube video URL")
        String youtubeUrl,

        @NotBlank(message = "targetLanguage must not be blank")
        @Pattern(regexp = TranscriptionRequestDto.TARGET_LANGUAGE_PATTERN, message = "targetLanguage must be an ISO 639-1 two-letter code")
        String targetLanguage) {

    // Exposed so the SSE streaming endpoint (query params, not a @RequestBody) validates against
    // the exact same rules instead of a second, driftable copy of these regexes.
    public static final String YOUTUBE_URL_PATTERN = "^https?://(www\\.|m\\.|music\\.)?(youtube\\.com/|youtu\\.be/).+$";
    public static final String TARGET_LANGUAGE_PATTERN = "^[a-z]{2}$";
}
