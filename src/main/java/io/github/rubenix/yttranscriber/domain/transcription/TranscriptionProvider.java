package io.github.rubenix.yttranscriber.domain.transcription;

public interface TranscriptionProvider {

    TranscriptionOutcome transcribe(TranscriptionRequest request);
}
