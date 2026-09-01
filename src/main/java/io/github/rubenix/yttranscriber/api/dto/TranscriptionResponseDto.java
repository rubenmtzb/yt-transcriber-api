package io.github.rubenix.yttranscriber.api.dto;

import io.github.rubenix.yttranscriber.application.TranscriptionResult;

import java.util.List;

public record TranscriptionResponseDto(
        VideoDto video,
        String sourceLanguage,
        String targetLanguage,
        String source,
        List<SegmentDto> segments) {

    public static TranscriptionResponseDto from(TranscriptionResult result) {
        VideoDto video = new VideoDto(result.video().id(), result.video().title(), result.video().durationSeconds());
        List<SegmentDto> segments = result.segments().stream()
                .map(s -> new SegmentDto(s.sequence(), s.startMs(), s.endMs(), s.sourceText(), s.translatedText(),
                        s.words().stream().map(w -> new TimedWordDto(w.text(), w.startMs(), w.endMs())).toList()))
                .toList();
        return new TranscriptionResponseDto(
                video, result.sourceLanguage(), result.targetLanguage(), result.source().name(), segments);
    }
}
