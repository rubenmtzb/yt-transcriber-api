package io.github.rubenix.yttranscriber.exception;

public final class RateLimitedException extends ApplicationException {

    public RateLimitedException(String message) {
        super(ErrorCode.RATE_LIMITED, message);
    }
}
