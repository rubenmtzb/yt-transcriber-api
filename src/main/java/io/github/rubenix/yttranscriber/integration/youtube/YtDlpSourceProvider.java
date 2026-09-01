package io.github.rubenix.yttranscriber.integration.youtube;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;
import io.github.rubenix.yttranscriber.domain.source.SourceProvider;
import io.github.rubenix.yttranscriber.domain.source.SourceRequest;
import io.github.rubenix.yttranscriber.domain.source.SourceResolution;
import io.github.rubenix.yttranscriber.domain.source.VideoMetadata;
import io.github.rubenix.yttranscriber.domain.transcription.TimedWord;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSegment;
import io.github.rubenix.yttranscriber.domain.transcription.TranscriptSource;
import io.github.rubenix.yttranscriber.exception.ProviderUnavailableException;
import io.github.rubenix.yttranscriber.exception.UnsupportedSourceException;
import io.github.rubenix.yttranscriber.integration.process.ExternalProcessRunner;
import io.github.rubenix.yttranscriber.integration.process.TempWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
            return new SourceResolution(video, info.language(), List.of(), TranscriptSource.SPEECH_TO_TEXT);
        }

        List<TranscriptSegment> segments = fetchSegments(request.youtubeUrl(), video.id(), track.get());
        TranscriptSource source = track.get().manual()
                ? TranscriptSource.MANUAL_CAPTIONS
                : TranscriptSource.AUTOMATIC_CAPTIONS;
        return new SourceResolution(video, track.get().language(), segments, source);
    }

    VideoMetadata fetchMetadata(String youtubeUrl) {
        return toVideoMetadata(fetchRawInfo(youtubeUrl));
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
        try (TempWorkspace workspace = TempWorkspace.create("ytdlp-subs-")) {
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

    /** Keeps the words inside the trimmed cue, so none of them outlives the line they belong to. */
    private List<TimedWord> clampWords(List<TimedWord> words, long endMs) {
        return words.stream()
                .filter(word -> word.startMs() < endMs)
                .map(word -> new TimedWord(word.text(), word.startMs(), Math.min(word.endMs(), endMs)))
                .toList();
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

        List<Json3Event> events = document.events() != null ? document.events() : List.of();
        List<TranscriptSegment> cues = new ArrayList<>();
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
            long endMs = startMs + durationMs;
            cues.add(new TranscriptSegment(sequence++, startMs, endMs, text, wordsOf(event, startMs, endMs)));
        }
        return clampToNextCue(cues);
    }

    /**
     * Reads the per-word timings YouTube ships alongside the text.
     *
     * Each {@code seg} is one word with a {@code tOffsetMs} measured from the cue's own start; the
     * first word omits it, meaning zero. Only starts are given, so a word runs until the next one
     * begins and the last runs to the end of the cue. Uploader-written tracks have a single seg per
     * cue and no offsets, which yields nothing here -- exactly the "no word timings" case callers
     * must already handle.
     */
    private List<TimedWord> wordsOf(Json3Event event, long cueStartMs, long cueEndMs) {
        record Start(String text, long startMs) {
        }

        List<Start> starts = new ArrayList<>();
        for (Json3Seg seg : event.segs()) {
            if (seg.utf8() == null || seg.utf8().isBlank()) {
                continue;
            }
            starts.add(new Start(seg.utf8(), cueStartMs + (seg.tOffsetMs() != null ? seg.tOffsetMs() : 0L)));
        }
        if (starts.size() < 2) {
            return List.of();
        }

        List<TimedWord> words = new ArrayList<>(starts.size());
        for (int index = 0; index < starts.size(); index++) {
            Start start = starts.get(index);
            long endMs = index + 1 < starts.size() ? starts.get(index + 1).startMs() : cueEndMs;
            words.add(new TimedWord(start.text(), start.startMs(), Math.max(start.startMs(), endMs)));
        }
        return words;
    }

    /**
     * Trims each cue so it ends where the next one begins.
     *
     * A cue's declared duration is how long YouTube leaves the line *on screen*, not how long it
     * takes to say: with rolling captions a line stays up while the next one appears underneath,
     * so most cues overrun their successor. Measured on a real auto-captioned video, 46 of 56 cues
     * ended after the next had already started, one of them claiming 15.3s for 12.1s of speech.
     * Left alone that inflation is inherited by the merged line and stretches anything derived from
     * the line's duration -- the read-along sweep drifts behind the audio and never reaches the end
     * of a line before the next one takes over.
     */
    private List<TranscriptSegment> clampToNextCue(List<TranscriptSegment> cues) {
        List<TranscriptSegment> clamped = new ArrayList<>(cues.size());
        for (int index = 0; index < cues.size(); index++) {
            TranscriptSegment cue = cues.get(index);
            long endMs = cue.endMs();
            if (index + 1 < cues.size()) {
                long nextStartMs = cues.get(index + 1).startMs();
                // Only ever shortens, and never past the cue's own start: out-of-order or
                // zero-length cues keep whatever the source declared.
                if (nextStartMs > cue.startMs() && nextStartMs < endMs) {
                    endMs = nextStartMs;
                }
            }
            clamped.add(new TranscriptSegment(
                    cue.sequence(), cue.startMs(), endMs, cue.text(), clampWords(cue.words(), endMs)));
        }
        return clamped;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Json3Document(List<Json3Event> events) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Json3Event(Long tStartMs, Long dDurationMs, List<Json3Seg> segs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Json3Seg(String utf8, Long tOffsetMs) {
    }
}
