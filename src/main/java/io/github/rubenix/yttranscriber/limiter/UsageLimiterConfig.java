package io.github.rubenix.yttranscriber.limiter;

import io.github.rubenix.yttranscriber.config.ProcessingLimitsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The two usage buckets every request is charged against.
 *
 * <p>Same machinery, different identity and different budget. The session bucket is the one a person
 * can see: it drives the remaining-allowance panel, and because a session is one browser it gives an
 * answer that matches what they just did. The address bucket is the one that actually holds, since
 * an address is not something a caller can pick. Charging only the first would leave the limits
 * decorative; charging only the second would make the panel lie to anyone sharing a router.
 */
@Configuration
public class UsageLimiterConfig {

    @Bean
    public UsageLimiter sessionUsageLimiter(ProcessingLimitsProperties limits, Clock clock) {
        return new UsageLimiter(limits, clock);
    }

    @Bean
    public UsageLimiter ipUsageLimiter(ProcessingLimitsProperties limits, Clock clock) {
        return new UsageLimiter(limits.asPerIpBudget(), clock);
    }
}
