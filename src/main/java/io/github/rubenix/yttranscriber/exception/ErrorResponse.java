package io.github.rubenix.yttranscriber.exception;

public record ErrorResponse(String code, String message, boolean retryable, String requestId) {
}
