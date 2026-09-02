package io.github.rubenix.yttranscriber.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The per-session budgets are what a person sees in the UI; the per-IP ones are what actually holds.
 * They are deliberately separate numbers: a session id is chosen by the caller and so cannot bound
 * anything (see {@link io.github.rubenix.yttranscriber.limiter.ClientIpFilter}), while an address is
 * shared by everyone behind one router -- a household, an office, a phone network -- so charging it
 * the same three requests an hour would lock out ordinary people who did nothing wrong. The per-IP
 * budget is therefore the looser of the two: generous enough that a real group of humans never
 * notices it, tight enough that one person cannot drain a month of translation quota.
 */
@Validated
@ConfigurationProperties(prefix = "app.processing")
public record ProcessingLimitsProperties(
        @Min(1) long maxVideoDurationSeconds,
        @Min(1) int maxRequestsPerHour,
        @Min(1) long maxAudioMinutesPerHour,
        @Min(1) int maxConcurrentTranscriptions,
        @Min(1) int maxRequestsPerHourPerIp,
        @Min(1) long maxAudioMinutesPerHourPerIp) {

    /**
     * The same limits with the per-IP budgets in the per-session slots, so one
     * {@link io.github.rubenix.yttranscriber.limiter.UsageLimiter} implementation can enforce either
     * bucket without knowing which one it is.
     */
    public ProcessingLimitsProperties asPerIpBudget() {
        return new ProcessingLimitsProperties(
                maxVideoDurationSeconds,
                maxRequestsPerHourPerIp,
                maxAudioMinutesPerHourPerIp,
                maxConcurrentTranscriptions,
                maxRequestsPerHourPerIp,
                maxAudioMinutesPerHourPerIp);
    }
}
