package io.github.rubenix.yttranscriber.integration.transcription;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * No bean validation on purpose (unlike YtDlpProperties): whisper-cli and a model file are not
 * installed by default, and the app must still start without them, the same way it starts without
 * a DeepL key. WhisperTranscriptionProvider fails fast per-request with ProviderUnavailableException
 * when modelPath is blank, instead of the app refusing to boot.
 */
@ConfigurationProperties(prefix = "app.whisper")
public record WhisperProperties(String binaryPath, String modelPath, long timeoutSeconds, long minAudioDurationSeconds) {
}
