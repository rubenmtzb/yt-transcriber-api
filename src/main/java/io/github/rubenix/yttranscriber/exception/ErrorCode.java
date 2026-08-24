package io.github.rubenix.yttranscriber.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, false),
    UNSUPPORTED_SOURCE(HttpStatus.UNPROCESSABLE_CONTENT, false),
    VIDEO_TOO_LONG(HttpStatus.CONTENT_TOO_LARGE, false),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true),
    PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true),
    TRANSLATION_QUOTA_EXCEEDED(HttpStatus.SERVICE_UNAVAILABLE, false),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false);

    private final HttpStatus httpStatus;
    private final boolean retryable;

    ErrorCode(HttpStatus httpStatus, boolean retryable) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}
