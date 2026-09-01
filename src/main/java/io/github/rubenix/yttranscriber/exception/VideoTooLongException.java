package io.github.rubenix.yttranscriber.exception;

public final class VideoTooLongException extends ApplicationException {

    public VideoTooLongException(String message) {
        super(ErrorCode.VIDEO_TOO_LONG, message);
    }
}
