package io.github.rubenix.yttranscriber.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads ./.env into the Spring Environment when present, so ./mvnw spring-boot:run and IDE run
 * configurations behave the same as a shell where .env was manually sourced. Added last, so real
 * environment variables (a production deployment, a shell export) always win over the file.
 *
 * <p>Uses System.out rather than a Logger: this runs before Spring's logging system is guaranteed
 * to be configured, and startup-time visibility here (never logging values, only the resolved
 * path and how many keys were found) is worth more than a properly formatted log line — a wrong
 * working directory (e.g. an IDE run configuration not set to the module root) is otherwise a
 * silent, hard-to-diagnose cause of "it works in my terminal but not from the IDE".
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path envFile = Path.of(".env").toAbsolutePath();
        if (!Files.isRegularFile(envFile)) {
            System.out.println("[dotenv] No .env file found at " + envFile + " — relying on real environment variables.");
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(envFile);
        } catch (IOException e) {
            System.out.println("[dotenv] Found " + envFile + " but could not read it: " + e.getMessage());
            return;
        }

        Map<String, Object> values = parse(lines);
        if (values.isEmpty()) {
            System.out.println("[dotenv] " + envFile + " exists but defines no usable KEY=VALUE lines.");
            return;
        }

        environment.getPropertySources().addLast(new MapPropertySource("dotenv", values));
        System.out.println("[dotenv] Loaded " + values.size() + " variable(s) from " + envFile + ": " + values.keySet());
    }

    static Map<String, Object> parse(List<String> lines) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            values.put(key, value);
        }
        return values;
    }
}
