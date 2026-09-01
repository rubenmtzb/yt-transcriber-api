package io.github.rubenix.yttranscriber.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DotenvEnvironmentPostProcessorTest {

    @Test
    void parsesKeyValueLines() {
        Map<String, Object> values = DotenvEnvironmentPostProcessor.parse(List.of(
                "TRANSLATION_API_KEY=abc123:fx",
                "MAX_REQUESTS_PER_HOUR=3"));

        assertThat(values)
                .containsEntry("TRANSLATION_API_KEY", "abc123:fx")
                .containsEntry("MAX_REQUESTS_PER_HOUR", "3");
    }

    @Test
    void ignoresBlankLinesAndComments() {
        Map<String, Object> values = DotenvEnvironmentPostProcessor.parse(List.of(
                "# a comment",
                "",
                "   ",
                "KEY=value"));

        assertThat(values).containsOnly(Map.entry("KEY", "value"));
    }

    @Test
    void ignoresMalformedLinesWithNoEqualsSign() {
        Map<String, Object> values = DotenvEnvironmentPostProcessor.parse(List.of("not-a-valid-line"));

        assertThat(values).isEmpty();
    }

    @Test
    void treatsAnEmptyValueAsValid() {
        Map<String, Object> values = DotenvEnvironmentPostProcessor.parse(List.of("SPEECH_API_KEY="));

        assertThat(values).containsEntry("SPEECH_API_KEY", "");
    }

    @Test
    void trimsWhitespaceAroundKeysAndValues() {
        Map<String, Object> values = DotenvEnvironmentPostProcessor.parse(List.of("  KEY  =  value with spaces  "));

        assertThat(values).containsEntry("KEY", "value with spaces");
    }
}
