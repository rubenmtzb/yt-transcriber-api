package io.github.rubenix.yttranscriber.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TranscriptionRequestDto(

        @NotBlank(message = "youtubeUrl must not be blank")
        @Size(max = TranscriptionRequestDto.MAX_URL_LENGTH, message = "youtubeUrl is too long")
        @Pattern(
                regexp = TranscriptionRequestDto.YOUTUBE_URL_PATTERN,
                message = "youtubeUrl must be a valid YouTube video URL")
        String youtubeUrl,

        @NotBlank(message = "targetLanguage must not be blank")
        @Pattern(regexp = TranscriptionRequestDto.TARGET_LANGUAGE_PATTERN, message = "targetLanguage must be an ISO 639-1 two-letter code")
        String targetLanguage) {

    // Exposed so the SSE streaming endpoint (query params, not a @RequestBody) validates against
    // the exact same rules instead of a second, driftable copy of these regexes.
    /**
     * The conventional ceiling for a URL, and far more than any YouTube link needs. The pattern
     * below anchors the scheme and host but ends in an unbounded {@code .+}, so without this a
     * megabyte of trailing characters is a valid request -- and it would be handed to yt-dlp as a
     * process argument and written to a log line on the way. Neither is a hole on its own; both are
     * work done on behalf of a caller who supplied nothing usable.
     */
    public static final int MAX_URL_LENGTH = 2048;

    public static final String YOUTUBE_URL_PATTERN = "^https?://(www\\.|m\\.|music\\.)?(youtube\\.com/|youtu\\.be/).+$";
    public static final String TARGET_LANGUAGE_PATTERN = "^[a-z]{2}$";
}
