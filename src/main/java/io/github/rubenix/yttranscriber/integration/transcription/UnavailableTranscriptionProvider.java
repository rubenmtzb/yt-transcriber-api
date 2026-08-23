package io.github.rubenix.yttranscriber.integration.transcription;

import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionProvider;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptionRequest;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default {@link TranscriptionProvider} until a real Speech-to-Text adapter is wired in.
 */
@Component
public class UnavailableTranscriptionProvider implements TranscriptionProvider {

    @Override
    public List<TranscriptSegment> transcribe(TranscriptionRequest request) {
        throw new ProviderUnavailableException("No transcription provider is configured yet.");
    }
}
