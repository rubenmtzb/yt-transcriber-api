package io.github.rubenix.yttranscriber.integration.youtube;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;
import io.github.rubenix.yttranscriber.domain.source.SourceProvider;
import io.github.rubenix.yttranscriber.domain.source.SourceRequest;
import io.github.rubenix.yttranscriber.domain.source.SourceResolution;
import io.github.rubenix.yttranscriber.domain.source.VideoMetadata;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import io.github.rubenix.yttranscriber.exception.UnsupportedSourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class YtDlpSourceProvider implements SourceProvider {

    private static final Logger log = LoggerFactory.getLogger(YtDlpSourceProvider.class);
    private static final Set<String> SUPPORTED_AVAILABILITY = Set.of("public", "unlisted");
    private static final String SOURCE_LANGUAGE = "en";

    private final YtDlpProcessRunner processRunner;
    private final ObjectMapper objectMapper;
    private final YtDlpProperties properties;

    public YtDlpSourceProvider(YtDlpProcessRunner processRunner, ObjectMapper objectMapper, YtDlpProperties properties) {
        this.processRunner = processRunner;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public SourceResolution resolve(SourceRequest request) {
        VideoMetadata video = fetchMetadata(request.youtubeUrl());
        List<TranscriptSegment> segments = fetchSegments(request.youtubeUrl(), video.id());
        return new SourceResolution(video, SOURCE_LANGUAGE, segments);
    }

    VideoMetadata fetchMetadata(String youtubeUrl) {
        List<String> command = List.of(
                properties.binaryPath(),
                "--skip-download",
                "--print", "%(.{id,title,duration,availability,is_live})j",
                youtubeUrl);

        var result = processRunner.run(command, Duration.ofSeconds(properties.timeoutSeconds()));

        if (result.exitCode() != 0) {
            throw new UnsupportedSourceException("The video could not be resolved: " + youtubeUrl);
        }

        RawVideoInfo info = parse(result.stdout());

        if (info.isLive()) {
            throw new UnsupportedSourceException("Live streams are not supported.");
        }
        if (info.availability() == null || !SUPPORTED_AVAILABILITY.contains(info.availability())) {
            throw new UnsupportedSourceException("This video is not publicly accessible.");
        }
        if (info.duration() == null) {
            throw new UnsupportedSourceException("Could not determine the video duration.");
        }

        return new VideoMetadata(info.id(), info.title(), info.duration());
    }

    private RawVideoInfo parse(String stdout) {
        try {
            return objectMapper.readValue(stdout.trim(), RawVideoInfo.class);
        } catch (Exception e) {
            throw new ProviderUnavailableException("Could not parse yt-dlp metadata output.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawVideoInfo(
            String id,
            String title,
            Long duration,
            String availability,
            @JsonProperty("is_live") boolean isLive) {
    }

    List<TranscriptSegment> fetchSegments(String youtubeUrl, String videoId) {
        Path tempDir = createTempDirectory();
        try {
            SubtitleAttempt manual = downloadSubtitleFile(youtubeUrl, videoId, tempDir, "--write-subs");
            if (manual.file().isPresent()) {
                return parseSegments(manual.file().get());
            }

            SubtitleAttempt auto = downloadSubtitleFile(youtubeUrl, videoId, tempDir, "--write-auto-subs");
            if (auto.file().isPresent()) {
                return parseSegments(auto.file().get());
            }

            if (manual.failed() || auto.failed()) {
                // A non-zero exit means yt-dlp itself hit a problem (e.g. YouTube rate-limiting
                // it with a 429) fetching a track we otherwise know might exist — that's not the
                // same as yt-dlp cleanly reporting "no such captions" (exit 0, no file).
                log.warn("yt-dlp failed to fetch subtitles for {}. manual: exit={}, stderr={} | auto: exit={}, stderr={}",
                        videoId, manual.exitCode(), manual.stderr(), auto.exitCode(), auto.stderr());
                throw new ProviderUnavailableException("Could not fetch captions from the source provider.");
            }

            throw new UnsupportedSourceException(
                    "No %s captions are available for this video.".formatted(SOURCE_LANGUAGE));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private SubtitleAttempt downloadSubtitleFile(String youtubeUrl, String videoId, Path tempDir, String subsFlag) {
        List<String> command = List.of(
                properties.binaryPath(),
                "--skip-download",
                subsFlag,
                "--sub-langs", SOURCE_LANGUAGE,
                "--sub-format", "json3",
                "-P", tempDir.toString(),
                "-o", "%(id)s",
                youtubeUrl);

        var result = processRunner.run(command, Duration.ofSeconds(properties.timeoutSeconds()));

        Path candidate = tempDir.resolve(videoId + "." + SOURCE_LANGUAGE + ".json3");
        Optional<Path> file = Files.exists(candidate) ? Optional.of(candidate) : Optional.empty();
        return new SubtitleAttempt(file, result.exitCode() != 0, result.exitCode(), result.stderr());
    }

    private record SubtitleAttempt(Optional<Path> file, boolean failed, int exitCode, String stderr) {
    }

    private List<TranscriptSegment> parseSegments(Path subtitleFile) {
        Json3Document document;
        try {
            document = objectMapper.readValue(Files.readString(subtitleFile), Json3Document.class);
        } catch (Exception e) {
            throw new ProviderUnavailableException("Could not parse the downloaded subtitle file.");
        }

        List<TranscriptSegment> segments = new ArrayList<>();
        List<Json3Event> events = document.events() != null ? document.events() : List.of();
        int sequence = 0;
        for (Json3Event event : events) {
            if (event.segs() == null || event.segs().isEmpty()) {
                continue;
            }
            String text = event.segs().stream()
                    .map(Json3Seg::utf8)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining())
                    .trim();
            if (text.isEmpty()) {
                continue;
            }
            long startMs = event.tStartMs() != null ? event.tStartMs() : 0L;
            long durationMs = event.dDurationMs() != null ? event.dDurationMs() : 0L;
            segments.add(new TranscriptSegment(sequence++, startMs, startMs + durationMs, text));
        }
        return segments;
    }

    private Path createTempDirectory() {
        try {
            return Files.createTempDirectory("ytdlp-subs-");
        } catch (IOException e) {
            throw new ProviderUnavailableException("Could not create a temporary directory for subtitle download.");
        }
    }

    private void deleteRecursively(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
        } catch (IOException ignored) {
            // best-effort cleanup; the OS will reclaim the temp dir eventually regardless
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException ignored) {
            // best-effort cleanup of an ephemeral temp file
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Json3Document(List<Json3Event> events) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Json3Event(Long tStartMs, Long dDurationMs, List<Json3Seg> segs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Json3Seg(String utf8) {
    }
}
