package io.github.rubenix.yttranscriber.api.dto;

public record TimedWordDto(String text, long startMs, long endMs) {
}
