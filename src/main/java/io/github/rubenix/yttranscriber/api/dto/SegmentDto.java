package io.github.rubenix.yttranscriber.api.dto;

public record SegmentDto(int sequence, long startMs, long endMs, String sourceText, String translatedText) {
}
