package io.github.rubenix.yttranscriber.exception;

public final class UnsupportedSourceException extends ApplicationException {

    public UnsupportedSourceException(String message) {
        super(ErrorCode.UNSUPPORTED_SOURCE, message);
    }
}
