package io.github.rubenix.yttranscriber.application;

import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.translation.TranslatedSegment;
import io.github.rubenix.yttranscriber.domain.translation.TranslationProvider;
import io.github.rubenix.yttranscriber.domain.translation.TranslationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranslationServiceTest {

    @Mock
    private TranslationProvider translationProvider;

    private TranslationService translationService;

    @BeforeEach
    void setUp() {
        translationService = new TranslationService(translationProvider);
    }

    @Test
    void echoesTheSourceTextBackWithoutCallingTheProviderWhenLanguagesMatch() {
        List<TranscriptSegment> segments = List.of(new TranscriptSegment(0, 0, 4200, "Hola a todos"));

        List<TranslatedSegment> result = translationService.translate(segments, "es", "es");

        assertThat(result).containsExactly(new TranslatedSegment(0, 0, 4200, "Hola a todos", "Hola a todos"));
        verify(translationProvider, never()).translate(any());
    }

    @Test
    void treatsLanguageCodesAsCaseInsensitive() {
        List<TranscriptSegment> segments = List.of(new TranscriptSegment(0, 0, 4200, "Hello"));

        translationService.translate(segments, "EN", "en");

        verify(translationProvider, never()).translate(any());
    }

    @Test
    void callsTheProviderWhenSourceAndTargetLanguagesDiffer() {
        List<TranscriptSegment> segments = List.of(new TranscriptSegment(0, 0, 4200, "Hello"));
        List<TranslatedSegment> translated = List.of(new TranslatedSegment(0, 0, 4200, "Hello", "Hola"));
        when(translationProvider.translate(new TranslationRequest(segments, "en", "es"))).thenReturn(translated);

        List<TranslatedSegment> result = translationService.translate(segments, "en", "es");

        assertThat(result).isEqualTo(translated);
    }
}
