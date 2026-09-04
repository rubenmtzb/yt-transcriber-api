package io.github.rubenix.yttranscriber.exception;

public sealed abstract class ApplicationException extends RuntimeException
        permits UnsupportedSourceException, VideoTooLongException, RateLimitedException, ProviderUnavailableException,
        TranslationQuotaExceededException {

    private final ErrorCode errorCode;

    protected ApplicationException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * For the failures that wrap a lower-level one. The message an adapter puts on these is
     * deliberately vague because it reaches the caller, which leaves the cause as the only record
     * of what actually broke -- dropping it is how a parse failure, a missing binary and a
     * permissions error all end up looking identical in the log.
     */
    protected ApplicationException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
