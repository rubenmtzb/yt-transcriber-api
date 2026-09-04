package io.github.rubenix.yttranscriber.exception;

public final class ProviderUnavailableException extends ApplicationException {

    public ProviderUnavailableException(String message) {
        super(ErrorCode.PROVIDER_UNAVAILABLE, message);
    }

    public ProviderUnavailableException(String message, Throwable cause) {
        super(ErrorCode.PROVIDER_UNAVAILABLE, message, cause);
    }
}
