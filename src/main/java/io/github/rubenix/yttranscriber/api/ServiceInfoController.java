package io.github.rubenix.yttranscriber.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Answers the service root. Deployments get hit on "/" constantly -- uptime pingers, the host's own
 * checks, scanners -- and until this existed every one of those was a 404 the error handler turned
 * into a logged 500. A cheap identifying response is more useful than a 404: it confirms which
 * service and which build is actually answering, without exposing anything an unauthenticated
 * caller couldn't already infer.
 *
 * <p>Deliberately not under /actuator: those endpoints are for operators, and this one has to stay
 * reachable even when actuator exposure is locked down.
 */
@RestController
public class ServiceInfoController {

    private final String name;
    private final String version;

    public ServiceInfoController(@Value("${spring.application.name:yt-transcriber-api}") String name,
                                  @Value("${app.version:dev}") String version) {
        this.name = name;
        this.version = version;
    }

    @GetMapping("/")
    public Map<String, String> root() {
        return Map.of("service", name, "version", version, "docs", "/api/v1/transcriptions");
    }
}
