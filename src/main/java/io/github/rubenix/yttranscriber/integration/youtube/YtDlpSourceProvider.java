package io.github.rubenix.yttranscriber.integration.youtube;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.rubenix.yttranscriber.domain.source.SourceProvider;
import io.github.rubenix.yttranscriber.domain.source.SourceRequest;
import io.github.rubenix.yttranscriber.domain.source.SourceResolution;
import io.github.rubenix.yttranscriber.domain.source.VideoMetadata;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import io.github.rubenix.yttranscriber.exception.UnsupportedSourceException;
import io.github.rubenix.yttranscriber.integration.process.ExternalProcessRunner;
import io.github.rubenix.yttranscriber.integration.process.TempWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class YtDlpSourceProvider implements SourceProvider {

    private static final Logger log = LoggerFactory.getLogger(YtDlpSourceProvider.class);
    private static final Set<String> SUPPORTED_AVAILABILITY = Set.of("public", "unlisted");
    private static final String AUTO_ORIGINAL_SUFFIX = "-orig";
    // Heuristic, not a full BCP-47 parser: matches "es", "pt-BR", "zh-Hans", "es-419" (region can
    // be a UN M49 numeric code). Rejects legacy community-contribution keys like
    // "es-ES-7eCR4kqQbL4", which are a real track id appended to the language, not a real tag.
    private static final Pattern CLEAN_LANGUAGE_CODE = Pattern.compile("^[a-z]{2,3}(-[A-Za-z0-9]{2,4})?$");

    private final ExternalProcessRunner processRunner;
    private final ObjectMapper objectMapper;
    private final YtDlpProperties properties;

    public YtDlpSourceProvider(ExternalProcessRunner processRunner, ObjectMapper objectMapper, YtDlpProperties properties) {
        this.processRunner = processRunner;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public SourceResolution resolve(SourceRequest request) {
        RawVideoInfo info = fetchRawInfo(request.youtubeUrl());
        VideoMetadata video = toVideoMetadata(info);

        Optional<CaptionTrack> track = selectCaptionTrack(info);
        if (track.isEmpty()) {
            // No usable captions in any language -- not a dead end. Empty segments tells
            // TranscriptionService to fall back to real Speech-to-Text (TranscriptionProvider).
            // info.language() is passed through as a hint (may be null); Whisper auto-detects
            // on its own when it's absent.
            return new SourceResolution(video, info.language(), List.of());
        }

        List<TranscriptSegment> segments = fetchSegments(request.youtubeUrl(), video.id(), track.get());
        return new SourceResolution(video, track.get().language(), segments);
    }

    private RawVideoInfo fetchRawInfo(String youtubeUrl) {
        List<String> command = List.of(
                properties.binaryPath(),
                "--skip-download",
                "--print", "%(.{id,title,duration,availability,is_live,language,subtitles,automatic_captions})j",
                youtubeUrl);

        var result = processRunner.run(command, Duration.ofSeconds(properties.timeoutSeconds()));

        if (result.exitCode() != 0) {
            throw new UnsupportedSourceException("The video could not be resolved: " + youtubeUrl);
        }

        return parse(result.stdout());
    }

    private VideoMetadata toVideoMetadata(RawVideoInfo info) {
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

    /**
     * Picks the caption track to use as the transcript source, in order of trust: a manual
     * (uploader-provided) track over an automatic one, and within automatic captions, the
     * original ASR-detected language (the {@code <lang>-orig} key) over any other language key --
     * every other key in {@code automatic_captions} is YouTube's own machine translation of the
     * original track, and feeding one of those into DeepL would translate a translation instead
     * of the source, compounding errors. Confirmed this schema empirically against real videos
     * (an English one exposing "en-orig", a Spanish one exposing manual subs under a plain "es").
     */
    Optional<CaptionTrack> selectCaptionTrack(RawVideoInfo info) {
        Optional<String> manual = pickLanguage(keysOf(info.subtitles()), info.language());
        if (manual.isPresent()) {
            return Optional.of(new CaptionTrack(manual.get(), true));
        }

        Set<String> autoKeys = keysOf(info.automaticCaptions());
        Optional<String> original = autoKeys.stream()
                .filter(key -> key.endsWith(AUTO_ORIGINAL_SUFFIX))
                .map(key -> key.substring(0, key.length() - AUTO_ORIGINAL_SUFFIX.length()))
                .findFirst();
        if (original.isPresent()) {
            return Optional.of(new CaptionTrack(original.get(), false));
        }

        if (info.language() != null && autoKeys.contains(info.language())) {
            return Optional.of(new CaptionTrack(info.language(), false));
        }

        return Optional.empty();
    }

    private Set<String> keysOf(Map<String, Object> tracks) {
        return tracks != null ? tracks.keySet() : Set.of();
    }

    private Optional<String> pickLanguage(Set<String> keys, String declaredLanguage) {
        List<String> clean = keys.stream().filter(key -> CLEAN_LANGUAGE_CODE.matcher(key).matches()).sorted().toList();
        if (declaredLanguage != null && clean.contains(declaredLanguage)) {
            return Optional.of(declaredLanguage);
        }
        return clean.stream().findFirst();
    }

    private RawVideoInfo parse(String stdout) {
        try {
            return objectMapper.readValue(stdout.trim(), RawVideoInfo.class);
        } catch (Exception e) {
            throw new ProviderUnavailableException("Could not parse yt-dlp metadata output.");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RawVideoInfo(
            String id,
            String title,
            Long duration,
            String availability,
            @JsonProperty("is_live") boolean isLive,
            String language,
            Map<String, Object> subtitles,
            @JsonProperty("automatic_captions") Map<String, Object> automaticCaptions) {
    }

    record CaptionTrack(String language, boolean manual) {
    }

    List<TranscriptSegment> fetchSegments(String youtubeUrl, String videoId, CaptionTrack track) {
        try (TempWorkspace workspace = TempWorkspace.create(
                "ytdlp-subs-", "Could not create a temporary directory for subtitle download.")) {
            SubtitleAttempt attempt = downloadSubtitleFile(youtubeUrl, videoId, workspace.directory(), track);
            if (attempt.file().isPresent()) {
                return parseSegments(attempt.file().get());
            }

            if (attempt.failed()) {
                // A non-zero exit means yt-dlp itself hit a problem (e.g. YouTube rate-limiting
                // it with a 429) fetching a track the metadata call told us should exist -- that's
                // not the same as yt-dlp cleanly reporting "no such captions" (exit 0, no file).
                log.warn("yt-dlp failed to fetch {} subtitles ({}) for {}: exit={}, stderr={}",
                        track.manual() ? "manual" : "auto-generated", track.language(), videoId,
                        attempt.exitCode(), attempt.stderr());
                throw new ProviderUnavailableException("Could not fetch captions from the source provider.");
            }

            throw new UnsupportedSourceException(
                    "No %s captions are available for this video.".formatted(track.language()));
        }
    }

    private SubtitleAttempt downloadSubtitleFile(String youtubeUrl, String videoId, Path tempDir, CaptionTrack track) {
        List<String> command = List.of(
                properties.binaryPath(),
                "--skip-download",
                track.manual() ? "--write-subs" : "--write-auto-subs",
                "--sub-langs", track.language(),
                "--sub-format", "json3",
                "-P", tempDir.toString(),
                "-o", "%(id)s",
                youtubeUrl);

        var result = processRunner.run(command, Duration.ofSeconds(properties.timeoutSeconds()));

        Optional<Path> file = findSubtitleFile(tempDir, videoId);
        return new SubtitleAttempt(file, result.exitCode() != 0, result.exitCode(), result.stderr());
    }

    private Optional<Path> findSubtitleFile(Path tempDir, String videoId) {
        // Scans instead of reconstructing the exact filename: yt-dlp names the file
        // "{id}.{lang}.json3", and scanning tolerates language-tag formatting we didn't
        // anticipate rather than silently missing a file that's really there.
        try (Stream<Path> paths = Files.list(tempDir)) {
            return paths
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(videoId + ".") && name.endsWith(".json3");
                    })
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
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
