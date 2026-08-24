package io.github.rubenix.yttranscriber.config;

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
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer(CorsProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(properties.allowedOrigins())
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders("X-Session-Id", "X-Request-Id")
                        .allowCredentials(true);
            }
        };
    }
}
