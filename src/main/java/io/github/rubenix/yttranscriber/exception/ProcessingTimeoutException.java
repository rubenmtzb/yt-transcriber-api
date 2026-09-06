package io.github.rubenix.yttranscriber.exception;

public final class ProcessingTimeoutException extends ApplicationException {

    public ProcessingTimeoutException() {
        super(ErrorCode.PROCESSING_TIMEOUT, "The transcription exceeded its processing time budget.");
    }
}
