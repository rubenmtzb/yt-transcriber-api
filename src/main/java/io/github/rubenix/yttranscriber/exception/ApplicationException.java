package io.github.rubenix.yttranscriber.exception;

public sealed abstract class ApplicationException extends RuntimeException
        permits UnsupportedSourceException, VideoTooLongException, RateLimitedException, ProviderUnavailableException,
        TranslationQuotaExceededException {

    private final ErrorCode errorCode;

    protected ApplicationException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
