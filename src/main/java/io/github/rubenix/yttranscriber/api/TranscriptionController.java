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
import io.github.rubenix.yttranscriber.limiter.ClientIpFilter;
import io.github.rubenix.yttranscriber.limiter.SessionIdFilter;
import io.github.rubenix.yttranscriber.limiter.UsageLimiter;
import io.github.rubenix.yttranscriber.limiter.UsageSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
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

@RestController
@RequestMapping("/api/v1/transcriptions")
@Validated
public class TranscriptionController {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionController.class);

    private final TranscriptionService transcriptionService;
    private final UsageLimiter sessionUsageLimiter;
    private final UsageLimiter ipUsageLimiter;

    public TranscriptionController(TranscriptionService transcriptionService,
                                    @Qualifier("sessionUsageLimiter") UsageLimiter sessionUsageLimiter,
                                    @Qualifier("ipUsageLimiter") UsageLimiter ipUsageLimiter) {
        this.transcriptionService = transcriptionService;
        this.sessionUsageLimiter = sessionUsageLimiter;
        this.ipUsageLimiter = ipUsageLimiter;
    }

    /**
     * What the caller has left of its hourly budget. Polled by the frontend so the limit can be
     * shown before a request is spent instead of only surfacing as a refusal afterwards.
     *
     * <p>Reports whichever of the two buckets is closer to refusing, so the figure shown is one the
     * next request would actually get rather than the friendlier of two answers.
     */
    @GetMapping("/usage")
    public UsageSnapshot usage(@RequestAttribute(SessionIdFilter.REQUEST_ATTRIBUTE) String sessionId,
                                @RequestAttribute(ClientIpFilter.REQUEST_ATTRIBUTE) String clientIp) {
        return UsageSnapshot.tighterOf(sessionUsageLimiter.remaining(sessionId), ipUsageLimiter.remaining(clientIp));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public TranscriptionResponseDto create(@Valid @RequestBody TranscriptionRequestDto request,
                                            @RequestAttribute(SessionIdFilter.REQUEST_ATTRIBUTE) String sessionId,
                                            @RequestAttribute(ClientIpFilter.REQUEST_ATTRIBUTE) String clientIp) {
        var result = transcriptionService.process(request.youtubeUrl(), request.targetLanguage(), sessionId, clientIp);
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
     *
     * <p>If the client goes away mid-stream the run is abandoned at the next stage boundary rather
     * than carried to completion -- see {@link TranscriptionStreamChannel}.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam @NotBlank @Pattern(regexp = TranscriptionRequestDto.YOUTUBE_URL_PATTERN) String youtubeUrl,
            @RequestParam @NotBlank @Pattern(regexp = TranscriptionRequestDto.TARGET_LANGUAGE_PATTERN) String targetLanguage,
            @RequestAttribute(SessionIdFilter.REQUEST_ATTRIBUTE) String sessionId,
            @RequestAttribute(ClientIpFilter.REQUEST_ATTRIBUTE) String clientIp) {
        SseEmitter emitter = new SseEmitter(0L);
        TranscriptionStreamChannel channel = new TranscriptionStreamChannel(emitter);
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);

        Thread.ofVirtual().start(() -> runStream(channel, youtubeUrl, targetLanguage, sessionId, clientIp, requestId));

        return emitter;
    }

    private void runStream(TranscriptionStreamChannel channel, String youtubeUrl, String targetLanguage,
                            String sessionId, String clientIp, String requestId) {
        // The filters that populate the MDC put their values on the request thread and clear them in
        // their own finally block; a virtual thread inherits none of it either way. Without this,
        // every line logged for the rest of the run -- the ones worth having when a stream fails --
        // prints an empty requestId and sessionId.
        MDC.put(RequestIdFilter.MDC_KEY, requestId);
        MDC.put(SessionIdFilter.MDC_KEY, sessionId);
        MDC.put(ClientIpFilter.MDC_KEY, clientIp);
        try {
            channel.sendSession(sessionId);
            channel.sendStage(ProcessingStage.VALIDATING_URL);

            TranscriptionResult result = transcriptionService.process(
                    youtubeUrl, targetLanguage, sessionId, clientIp, channel::sendStage);

            channel.sendResult(TranscriptionResponseDto.from(result));
        } catch (TranscriptionStreamChannel.StreamAborted e) {
            log.info("Client disconnected; abandoning the in-flight transcription for session {}", sessionId);
        } catch (ApplicationException e) {
            log.warn("Business rule violation during streaming: code={}, message={}", e.errorCode(), e.getMessage());
            channel.sendError(ErrorResponse.of(e.errorCode(), e.getMessage(), requestId));
        } catch (Exception e) {
            log.error("Unhandled exception during streaming", e);
            channel.sendError(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", requestId));
        } finally {
            channel.complete();
            MDC.clear();
        }
    }
}
