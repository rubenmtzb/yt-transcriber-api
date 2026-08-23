package io.github.rubenix.yttranscriber.domain.transcription;

import java.util.List;

public interface TranscriptionProvider {

    List<TranscriptSegment> transcribe(TranscriptionRequest request);
}
