# YT Transcriber API

Backend for YT Transcriber: takes a YouTube URL, resolves its transcript, translates it into the requested language, and returns it.

## Overview

A public, login-free API. There are no user accounts, no persisted history, and no database — every request is processed synchronously and nothing is stored beyond the lifetime of the request. Integrations with external providers sit behind ports/interfaces so the actual source, transcription, and translation providers can be swapped without touching the domain or application layers.

## Features

**Implemented**

- `POST /api/v1/transcriptions` endpoint with request validation.
- Layered architecture (`api` → `application` → `domain`, with `integration` adapters behind ports).
- Real source resolution: `yt-dlp` (invoked as a subprocess) fetches video metadata and English captions (manual, falling back to auto-generated) for any public YouTube video.
- Real translation via the DeepL API, once `TRANSLATION_API_KEY` is configured.
- Centralized error handling with a stable JSON error envelope and per-request correlation id.
- Actuator health/info/metrics endpoints with Prometheus scraping.
- Structured, request-id-correlated logging.
- Configurable processing limits (max video duration, requests/hour, audio-minutes/hour).

**Planned**

- Real transcription provider (Speech-to-Text) — currently dormant: not needed while `yt-dlp` supplies caption text directly, kept as a fallback path for videos without English captions.
- Anonymous session cookie, IP rate limiting, and quota enforcement (`limiter/`, `security/`, `domain/session/`).
- CORS configuration for the `yt-transcriber-web` frontend.
- TXT/SRT/VTT export from the segment model.
- Source-language coverage beyond English captions.

## Architecture

```text
Browser
   |
Astro + React (yt-transcriber-web)
   | HTTPS / JSON
Spring Boot API (this repo)
   api            -> HTTP boundary, request/response DTOs, validation
   application     -> use-case orchestration (TranscriptionService, SourceResolutionService, TranslationService)
   domain          -> ports and models (source, transcription, translation)
   integration     -> adapters implementing the domain ports (youtube, transcription, translation, http)
   exception       -> error envelope + centralized exception handling
   config          -> technical Spring configuration (properties, request-id filter)
```

The domain layer has no dependency on Spring, HTTP clients, or any provider SDK. `SourceProvider` and `TranslationProvider` are backed by real adapters (`yt-dlp`, DeepL). `TranscriptionProvider` still has no real implementation wired in — it stays behind an `UnavailableTranscriptionProvider` that fails cleanly with `PROVIDER_UNAVAILABLE` and is only reached if a source ever returns no ready-made segments.

## Tech Stack

- Java 25 (LTS)
- Spring Boot 4.1.1 (Web MVC, Validation, Actuator)
- Micrometer + Prometheus
- Maven (via Maven Wrapper)
- JUnit 5, Mockito, AssertJ, MockMvc
- [`yt-dlp`](https://github.com/yt-dlp/yt-dlp) (external binary, invoked as a subprocess) for source resolution
- [DeepL API](https://www.deepl.com/en/docs-api) for translation

## Getting Started

```bash
./mvnw clean verify   # build + test
./mvnw spring-boot:run
```

The app starts on `http://localhost:8080`. Health check:

```bash
curl http://localhost:8080/actuator/health
```

### Docker

```bash
docker build -t yt-transcriber-api .
docker run -p 8080:8080 --env-file .env yt-transcriber-api
```

## Configuration

Copy `.env.example` to `.env` and fill in the values you need locally.

| Variable                     | Default   | Description                                              |
|-------------------------------|-----------|------------------------------------------------------------|
| `SPEECH_API_KEY`              | (empty)   | Reserved for a future Speech-to-Text provider (unused)     |
| `TRANSLATION_API_KEY`         | (empty)   | DeepL API key. Without it, translation fails with `PROVIDER_UNAVAILABLE` |
| `MAX_VIDEO_DURATION_SECONDS`  | 1200      | Longest video accepted (20 minutes)                         |
| `MAX_REQUESTS_PER_HOUR`       | 3         | Processings per session per hour                            |
| `MAX_AUDIO_MINUTES_PER_HOUR`  | 60        | Audio-minutes budget per hour                               |
| `YTDLP_BINARY_PATH`           | `yt-dlp`  | Path to the yt-dlp executable                               |
| `YTDLP_TIMEOUT_SECONDS`       | 45        | Timeout for each yt-dlp subprocess call                     |

Actuator exposure: `health`, `info`, `metrics`, `prometheus` — see `application.yml`.

### Local requirements

`yt-dlp` must be installed and on `PATH` (`brew install yt-dlp` on macOS). It depends on `deno` to solve YouTube's bot-detection challenges — Homebrew installs it automatically as a dependency.

## Project Structure

```text
src/main/java/io/github/rubenix/yttranscriber/
  api/                  TranscriptionController + DTOs
  application/          Use-case services
  domain/
    source/             VideoMetadata, SourceProvider port
    transcription/       TranscriptSegment, TranscriptionProvider port
    translation/         TranslatedSegment, TranslationProvider port
  integration/
    http/                Shared RestClient.Builder factory (timeouts, prototype-scoped)
    youtube/             YtDlpSourceProvider + YtDlpProcessRunner (real)
    transcription/        TranscriptionProvider adapter (placeholder, dormant)
    translation/          DeepLTranslationProvider (real)
  exception/            Error codes, error envelope, global handler
  config/                Processing limits properties, request-id filter
```

`limiter/`, `security/`, and `domain/session/` are planned packages for anonymous-session and quota enforcement; they are not populated yet since there is no real behavior to put in them at this stage.

## Related Repository

Frontend: [yt-transcriber-web](https://github.com/rubenmtzb/yt-transcriber-web)

## Live Demo

Not deployed yet.

## License

[MIT](LICENSE)
