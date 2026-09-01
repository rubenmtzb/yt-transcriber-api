package io.github.rubenix.yttranscriber.exception;

public final class TranslationQuotaExceededException extends ApplicationException {

    public TranslationQuotaExceededException(String message) {
        super(ErrorCode.TRANSLATION_QUOTA_EXCEEDED, message);
    }
}
