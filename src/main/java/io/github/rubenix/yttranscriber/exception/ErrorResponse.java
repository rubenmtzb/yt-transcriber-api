package io.github.rubenix.yttranscriber.exception;

public record ErrorResponse(String code, String message, boolean retryable, String requestId) {

    public static ErrorResponse of(ErrorCode code, String message, String requestId) {
        return new ErrorResponse(code.name(), message, code.retryable(), requestId);
    }
}
