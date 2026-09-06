package io.github.rubenix.yttranscriber.integration.http;

import com.sun.net.httpserver.HttpServer;
import io.github.rubenix.yttranscriber.application.ProcessingBudget;
import io.github.rubenix.yttranscriber.exception.ProcessingTimeoutException;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestClientConfigTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(5)
    void aSilentHttpProviderCannotOutliveTheRemainingBudget(boolean sendsHeaders) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    if (sendsHeaders) {
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write('a');
                        exchange.getResponseBody().flush();
                    }
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            var client = new RestClientConfig().restClientBuilder().build();
            long start = System.nanoTime();
            try {
                assertThatThrownBy(() -> ProcessingBudget.run(Duration.ofMillis(250), () ->
                        client.get().uri("http://127.0.0.1:" + server.getAddress().getPort()).retrieve().body(String.class)))
                        .isInstanceOf(ProcessingTimeoutException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
            } finally {
                release.countDown();
                server.stop(0);
            }
        }
    }
}
