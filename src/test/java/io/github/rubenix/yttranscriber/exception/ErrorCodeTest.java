package io.github.rubenix.yttranscriber.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void everyErrorCodeHasAnHttpStatus(ErrorCode code) {
        assertThat(code.httpStatus()).isNotNull();
    }

    @Test
    void onlyTransientFailuresAreMarkedRetryable() {
        assertThat(ErrorCode.RATE_LIMITED.retryable()).isTrue();
        assertThat(ErrorCode.PROVIDER_UNAVAILABLE.retryable()).isTrue();

        assertThat(ErrorCode.INVALID_REQUEST.retryable()).isFalse();
        assertThat(ErrorCode.UNSUPPORTED_SOURCE.retryable()).isFalse();
        assertThat(ErrorCode.VIDEO_TOO_LONG.retryable()).isFalse();
        assertThat(ErrorCode.INTERNAL_ERROR.retryable()).isFalse();
    }

    @Test
    void statusCodesMatchTheDocumentedMapping() {
        assertThat(ErrorCode.INVALID_REQUEST.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.UNSUPPORTED_SOURCE.httpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ErrorCode.VIDEO_TOO_LONG.httpStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(ErrorCode.RATE_LIMITED.httpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(ErrorCode.PROVIDER_UNAVAILABLE.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ErrorCode.INTERNAL_ERROR.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
