package io.github.rubenix.yttranscriber.exception;

import io.github.rubenix.yttranscriber.config.RequestIdFilter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Both validation handlers answer with the same deliberately generic message: echoing which
    // constraint failed would leak the exact accepted URL shapes back to a caller probing the
    // endpoint. The specifics go to the log at debug instead, where they are still there to
    // diagnose a frontend sending a malformed request.

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        log.debug("Request body validation failed: {}", ex.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, "The request payload is invalid.");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        log.debug("Request parameter validation failed: {}", ex.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, "The request payload is invalid.");
    }

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ErrorResponse> handleApplication(ApplicationException ex) {
        log.warn("Business rule violation: code={}, message={}", ex.errorCode(), ex.getMessage());
        return respond(ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.");
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        return ResponseEntity.status(code.httpStatus()).body(ErrorResponse.of(code, message, requestId));
    }
}
