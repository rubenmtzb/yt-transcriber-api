package io.github.rubenix.yttranscriber.api;

import io.github.rubenix.yttranscriber.application.TranscriptionResult;
import io.github.rubenix.yttranscriber.application.TranscriptionService;
import io.github.rubenix.yttranscriber.domain.source.VideoMetadata;
import io.github.rubenix.yttranscriber.domain.translation.TranslatedSegment;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TranscriptionController.class)
class TranscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranscriptionService transcriptionService;

    @Test
    void returnsTheTranscriptionForAValidRequest() throws Exception {
        VideoMetadata video = new VideoMetadata("dQw4w9WgXcQ", "Sample video", 300);
        List<TranslatedSegment> segments = List.of(new TranslatedSegment(0, 0, 4200, "Hello everybody", "Hola a todos"));
        when(transcriptionService.process(anyString(), anyString()))
                .thenReturn(new TranscriptionResult(video, "en", "es", segments));

        mockMvc.perform(post("/api/v1/transcriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"youtubeUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","targetLanguage":"es"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.video.id").value("dQw4w9WgXcQ"))
                .andExpect(jsonPath("$.targetLanguage").value("es"))
                .andExpect(jsonPath("$.segments[0].translatedText").value("Hola a todos"));
    }

    @Test
    void rejectsABlankYoutubeUrlWithAnInvalidRequestEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/transcriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"youtubeUrl":"","targetLanguage":"es"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void rejectsAMalformedYoutubeUrl() throws Exception {
        mockMvc.perform(post("/api/v1/transcriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"youtubeUrl":"https://not-youtube.com/watch?v=abc","targetLanguage":"es"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void acceptsRealWorldYoutubeUrlShapesThatArentJustWatchVEqualsFirst() throws Exception {
        VideoMetadata video = new VideoMetadata("dQw4w9WgXcQ", "Sample video", 300);
        when(transcriptionService.process(anyString(), anyString()))
                .thenReturn(new TranscriptionResult(video, "en", "es", List.of()));

        // playlist link where "v" isn't the first query param, m.youtube.com, and Shorts
        for (String url : List.of(
                "https://www.youtube.com/watch?list=PLxyz&v=dQw4w9WgXcQ",
                "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
                "https://www.youtube.com/shorts/dQw4w9WgXcQ")) {
            mockMvc.perform(post("/api/v1/transcriptions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"youtubeUrl\":\"%s\",\"targetLanguage\":\"es\"}".formatted(url)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void mapsProviderUnavailableToServiceUnavailable() throws Exception {
        when(transcriptionService.process(anyString(), anyString()))
                .thenThrow(new ProviderUnavailableException("No source provider is configured yet."));

        mockMvc.perform(post("/api/v1/transcriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"youtubeUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","targetLanguage":"es"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }
}
