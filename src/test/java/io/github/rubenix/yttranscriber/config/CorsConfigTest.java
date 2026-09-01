package io.github.rubenix.yttranscriber.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises real preflight handling against the embedded server (not a MockMvc slice) —
 * CORS is enforced by the browser based on response headers, not by the server returning
 * an error status, so this needs the real HTTP stack to be meaningful.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsConfigTest {

    @LocalServerPort
    private int port;

    @Test
    void allowsPreflightFromTheConfiguredFrontendOrigin() {
        ResponseEntity<Void> response = RestClient.create().options()
                .uri("http://localhost:%d/api/v1/transcriptions".formatted(port))
                .header(HttpHeaders.ORIGIN, "http://localhost:4321")
                .header("Access-Control-Request-Method", "POST")
                .retrieve()
                .toBodilessEntity();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Origin")).isEqualTo("http://localhost:4321");
        assertThat(response.getHeaders().getFirst("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    @Test
    void rejectsPreflightFromAnUnknownOriginWith403() {
        assertThatThrownBy(() -> RestClient.create().options()
                .uri("http://localhost:%d/api/v1/transcriptions".formatted(port))
                .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                .header("Access-Control-Request-Method", "POST")
                .retrieve()
                .toBodilessEntity())
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }
}
