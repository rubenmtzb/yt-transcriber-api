package io.github.rubenix.yttranscriber.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Locks CORS down to the configured frontend origin(s) only — never a wildcard, since
 * allowCredentials(true) is kept for forward compatibility and browsers reject wildcard origins
 * combined with credentials anyway. Exposes X-Session-Id/X-Request-Id so frontend JS can read
 * them off the response — custom response headers are invisible to fetch() on a cross-origin
 * response unless explicitly exposed, even though the CORS request itself succeeds.
 *
 * <p>An empty origin list is shouted about rather than accepted quietly. It produces an API that
 * answers curl perfectly and refuses every browser with "Invalid CORS request", which is a
 * genuinely hard failure to place from the outside -- the service looks healthy from every angle
 * except the only one that matters. It happened on the first deployment, from an environment
 * variable set to a value that matched nothing.
 */
@Configuration
public class CorsConfig {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    @Bean
    public WebMvcConfigurer corsConfigurer(CorsProperties properties) {
        if (properties.allowedOrigins() == null || properties.allowedOrigins().isEmpty()) {
            log.error("app.cors.allowed-origins (CORS_ALLOWED_ORIGINS) is empty -- every browser "
                    + "request to this API will be refused with 'Invalid CORS request'. Set it to the "
                    + "frontend origin(s), comma separated.");
        } else {
            log.info("CORS enabled for origins: {}", properties.allowedOrigins());
        }
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders("X-Session-Id", "X-Request-Id")
                        .allowCredentials(true);
            }
        };
    }
}
