package io.github.rubenix.yttranscriber.api;

import io.github.rubenix.yttranscriber.api.dto.TranscriptionRequestDto;
import io.github.rubenix.yttranscriber.api.dto.TranscriptionResponseDto;
import io.github.rubenix.yttranscriber.application.ProcessingStage;
import io.github.rubenix.yttranscriber.application.TranscriptionResult;
import io.github.rubenix.yttranscriber.application.TranscriptionService;
import io.github.rubenix.yttranscriber.config.RequestIdFilter;
import io.github.rubenix.yttranscriber.exception.ApplicationException;
import io.github.rubenix.yttranscriber.exception.ErrorCode;
import io.github.rubenix.yttranscriber.exception.ErrorResponse;
import io.github.rubenix.yttranscriber.limiter.SessionIdFilter;
import io.github.rubenix.yttranscriber.limiter.UsageLimiter;
import io.github.rubenix.yttranscriber.limiter.UsageSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/transcriptions")
@Validated
public class TranscriptionController {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionController.class);

    private final TranscriptionService transcriptionService;
    private final UsageLimiter usageLimiter;

    public TranscriptionController(TranscriptionService transcriptionService, UsageLimiter usageLimiter) {
        this.transcriptionService = transcriptionService;
        this.usageLimiter = usageLimiter;
    }

    /**
     * What the caller has left of its hourly budget. Polled by the frontend so the limit can be
     * shown before a request is spent instead of only surfacing as a refusal afterwards.
     */
    @GetMapping("/usage")
    public UsageSnapshot usage(@RequestAttribute(SessionIdFilter.REQUEST_ATTRIBUTE) String sessionId) {
        return usageLimiter.remaining(sessionId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public TranscriptionResponseDto create(@Valid @RequestBody TranscriptionRequestDto request,
                                            @RequestAttribute(SessionIdFilter.REQUEST_ATTRIBUTE) String sessionId) {
        var result = transcriptionService.process(request.youtubeUrl(), request.targetLanguage(), sessionId);
        return TranscriptionResponseDto.from(result);
    }

    /**
     * Same use case as {@link #create}, streamed as Server-Sent Events instead of one blocking
     * response: a "session" event (browser EventSource can't set request headers, so the session
     * id -- normally a response header, see SessionIdFilter -- has to travel as event data
     * instead), then "stage" events as processing moves through {@link ProcessingStage}, and
     * finally either a "result" or an "error" event. GET only: EventSource cannot issue POSTs, so
     * the request body becomes query parameters, validated against the exact same rules as the
     * POST endpoint's body (see the shared pattern constants on the DTO).
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam @NotBlank @Pattern(regexp = TranscriptionRequestDto.YOUTUBE_URL_PATTERN) String youtubeUrl,
            @RequestParam @NotBlank @Pattern(regexp = TranscriptionRequestDto.TARGET_LANGUAGE_PATTERN) String targetLanguage,
            @RequestAttribute(SessionIdFilter.REQUEST_ATTRIBUTE) String sessionId) {
        SseEmitter emitter = new SseEmitter(0L);
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);

        Thread.ofVirtual().start(() -> runStream(emitter, youtubeUrl, targetLanguage, sessionId, requestId));

        return emitter;
    }

    private void runStream(SseEmitter emitter, String youtubeUrl, String targetLanguage, String sessionId, String requestId) {
        try {
            emitter.send(SseEmitter.event().name("session").data(sessionId));
            emitter.send(SseEmitter.event().name("stage").data(ProcessingStage.VALIDATING_URL.name()));

            TranscriptionResult result = transcriptionService.process(
                    youtubeUrl, targetLanguage, sessionId, stage -> sendStage(emitter, stage));

            emitter.send(SseEmitter.event().name("result").data(TranscriptionResponseDto.from(result)));
            emitter.complete();
        } catch (ApplicationException e) {
            log.warn("Business rule violation during streaming: code={}, message={}", e.errorCode(), e.getMessage());
            sendError(emitter, ErrorResponse.of(e.errorCode(), e.getMessage(), requestId));
            emitter.complete();
        } catch (Exception e) {
            log.error("Unhandled exception during streaming", e);
            sendError(emitter, ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", requestId));
            emitter.complete();
        }
    }

    private void sendStage(SseEmitter emitter, ProcessingStage stage) {
        try {
            // .data(String) is written to the wire as-is; .data(Object) instead runs it through
            // the JSON message converter, which would wrap a bare enum in quotes ("RESOLVING_VIDEO"
            // instead of RESOLVING_VIDEO) -- confirmed with a raw curl trace of the stream, since
            // the frontend comparing that raw event data against ProcessingStage string literals
            // would then silently never match past the very first (hardcoded, pre-stream) stage.
            emitter.send(SseEmitter.event().name("stage").data(stage.name()));
        } catch (IOException e) {
            // client disconnected; the in-flight call still runs to completion (no cheap way to
            // cancel a blocking subprocess-backed call mid-flight) but its result now has nowhere
            // to go, so subsequent sends on this emitter will just keep hitting this same branch
        }
    }

    private void sendError(SseEmitter emitter, ErrorResponse error) {
        try {
            emitter.send(SseEmitter.event().name("error").data(error));
        } catch (IOException ignored) {
            // client already gone
        }
    }
}
