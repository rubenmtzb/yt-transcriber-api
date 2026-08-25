# YT Transcriber API

Backend for YT Transcriber: takes a YouTube URL, resolves its transcript, translates it into the requested language, and returns it.

## Overview

A public, login-free API. There are no user accounts, no persisted history, and no database — every request is processed synchronously and nothing is stored beyond the lifetime of the request. Integrations with external providers sit behind ports/interfaces so the actual source, transcription, and translation providers can be swapped without touching the domain or application layers.

## Features

- `POST /api/v1/transcriptions` — one blocking request/response, with request validation.
- `GET /api/v1/transcriptions/stream` — the same use case over Server-Sent Events, reporting real progress as it happens.
- Layered architecture (`api` → `application` → `domain`, with `integration` adapters behind ports).
- Source resolution via `yt-dlp` (invoked as a subprocess): video metadata plus captions in **any** language, preferring uploader-provided subtitles over auto-generated ones, and the original ASR track over YouTube's own machine translations of it.
- Local Speech-to-Text fallback via [whisper.cpp](https://github.com/ggml-org/whisper.cpp) for videos with no captions in any language — runs entirely on the machine, no external API and no per-request cost.
- Caption cues grouped into sentence-level units before translation, so the translator sees whole sentences instead of on-screen line wraps.
- Translation via the DeepL API, with its per-minute rate limit and its monthly quota mapped to distinct error codes.
- Anonymous per-session rate limiting (requests/hour and audio-minutes/hour) plus a global concurrency guard.
- CORS locked to the configured frontend origin(s).
- Centralized error handling with a stable JSON error envelope and a per-request correlation id.
- Structured logging correlated by request id and session id.
- Actuator health/info/metrics endpoints with Prometheus scraping.

### Not implemented yet

- TXT/SRT/VTT export from the segment model.
- IP-based rate limiting (the current limiter keys on an anonymous session id; see `UsageLimiter`).
- Persistence of any kind — deliberately out of scope.

## API

### `POST /api/v1/transcriptions`

```bash
curl -X POST http://localhost:8080/api/v1/transcriptions \
  -H 'Content-Type: application/json' \
  -d '{"youtubeUrl": "https://www.youtube.com/watch?v=VIDEO_ID", "targetLanguage": "es"}'
```

```json
{
  "video": { "id": "VIDEO_ID", "title": "Example video", "durationSeconds": 213 },
  "sourceLanguage": "en",
  "targetLanguage": "es",
  "segments": [
    { "sequence": 0, "startMs": 0, "endMs": 4200, "sourceText": "Hello there.", "translatedText": "Hola." }
  ]
}
```

`targetLanguage` is an ISO 639-1 two-letter code. `sourceLanguage` is detected, not requested.

### `GET /api/v1/transcriptions/stream`

The same processing, streamed. It exists because the pipeline can take minutes on the Speech-to-Text path, and a browser waiting on a single blocking response has nothing to show meanwhile.

```bash
curl -N 'http://localhost:8080/api/v1/transcriptions/stream?youtubeUrl=https://www.youtube.com/watch?v=VIDEO_ID&targetLanguage=es'
```

It is a `GET` with query parameters rather than a `POST` with a body because the browser's native `EventSource` cannot issue POSTs — the parameters are validated against the exact same constraints as the POST body. Events emitted:

| Event     | Data                                                                                         |
|-----------|----------------------------------------------------------------------------------------------|
| `session` | The session id for this stream — `EventSource` cannot set request headers, so it cannot read the `X-Session-Id` response header either, and the id has to arrive as event data |
| `stage`   | `VALIDATING_URL`, `RESOLVING_VIDEO`, `TRANSCRIBING`, `TRANSLATING`, `PREPARING_RESULT` (raw, unquoted) |
| `result`  | The same JSON body as the POST endpoint                                                       |
| `error`   | The error envelope below                                                                     |

### Errors

Every failure returns the same envelope, on both endpoints:

```json
{ "code": "VIDEO_TOO_LONG", "message": "…", "retryable": false, "requestId": "…" }
```

| Code                         | HTTP | Retryable | Meaning                                            |
|------------------------------|------|-----------|----------------------------------------------------|
| `INVALID_REQUEST`            | 400  | no        | Malformed URL or target language                    |
| `UNSUPPORTED_SOURCE`         | 422  | no        | Private video, live stream, or too short to transcribe |
| `VIDEO_TOO_LONG`             | 413  | no        | Over `MAX_VIDEO_DURATION_SECONDS`                   |
| `RATE_LIMITED`               | 429  | yes       | Session budget spent, or the server is at capacity  |
| `PROVIDER_UNAVAILABLE`       | 503  | yes       | An upstream provider or local binary failed         |
| `TRANSLATION_QUOTA_EXCEEDED` | 503  | no        | DeepL's monthly character quota is used up          |
| `INTERNAL_ERROR`             | 500  | no        | Unhandled failure                                   |

`requestId` echoes the `X-Request-Id` response header, so a report from the frontend can be traced straight to the matching log lines.

## Architecture

```text
Browser
   |
Astro + React (yt-transcriber-web)
   | HTTPS / JSON + SSE
Spring Boot API (this repo)
   api           -> HTTP boundary, request/response DTOs, validation
   application   -> use-case orchestration (TranscriptionService, SourceResolutionService,
                    TranslationService, SentenceGrouper)
   domain        -> ports and models (source, transcription, translation)
   integration   -> adapters implementing the domain ports (youtube, transcription, translation,
                    http, process)
   limiter       -> anonymous session identity, per-session budgets, global capacity guard
   exception     -> error envelope + centralized exception handling
   config        -> technical Spring configuration (properties, CORS, request-id filter, dotenv)
```

The domain layer has no dependency on Spring, HTTP clients, or any provider SDK — it is ports and records only. Each port has exactly one adapter today (`YtDlpSourceProvider`, `WhisperTranscriptionProvider`, `DeepLTranslationProvider`), and swapping any of them touches nothing above the `integration` package.

The two subprocess-backed adapters share `ExternalProcessRunner` (bounded timeout, stdout/stderr drained on separate threads so a full pipe buffer cannot deadlock the child) and `TempWorkspace` (a throwaway output directory tied to the run via try-with-resources).

Transcription is a fallback, not the main path: `SourceProvider` returns empty segments when a video has no usable captions in any language, and only then does `TranscriptionService` invoke `TranscriptionProvider`. With no Whisper model configured, that path fails cleanly with `PROVIDER_UNAVAILABLE` and everything else keeps working.

## Tech Stack

- Java 25 (LTS)
- Spring Boot 4.1.1 (Web MVC, Validation, Actuator)
- Micrometer + Prometheus
- Maven (via Maven Wrapper)
- JUnit 5, Mockito, AssertJ, MockMvc
- [`yt-dlp`](https://github.com/yt-dlp/yt-dlp) (external binary, invoked as a subprocess) for source resolution
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (external binary) for local Speech-to-Text
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

The image bundles `yt-dlp`, `ffmpeg`, `whisper-cli` and a `ggml-base.bin` model, so the Speech-to-Text path works out of the box — note that CPU-only inference in a container is meaningfully slower than a Mac's Metal-accelerated GPU.

## Configuration

Copy `.env.example` to `.env` and fill in the values you need locally.

| Variable                             | Default       | Description                                                  |
|--------------------------------------|---------------|--------------------------------------------------------------|
| `TRANSLATION_API_KEY`                | (empty)       | DeepL API key. Without it, translation fails with `PROVIDER_UNAVAILABLE` |
| `MAX_VIDEO_DURATION_SECONDS`         | 1200          | Longest video accepted (20 minutes)                          |
| `MAX_REQUESTS_PER_HOUR`              | 3             | Processings per session per hour                             |
| `MAX_AUDIO_MINUTES_PER_HOUR`         | 60            | Audio-minutes budget per session per hour                    |
| `MAX_CONCURRENT_TRANSCRIPTIONS`      | 2             | Global cap on transcriptions processed at once               |
| `CORS_ALLOWED_ORIGINS`               | `http://localhost:4321` | Comma-separated frontend origins allowed to call the API |
| `YTDLP_BINARY_PATH`                  | `yt-dlp`      | Path to the yt-dlp executable                                |
| `YTDLP_TIMEOUT_SECONDS`              | 45            | Timeout for each yt-dlp subprocess call                      |
| `WHISPER_BINARY_PATH`                | `whisper-cli` | Path to the whisper.cpp CLI executable                       |
| `WHISPER_MODEL_PATH`                 | (empty)       | Path to a ggml model file. Empty disables local Speech-to-Text (videos with no captions in any language then fail with `PROVIDER_UNAVAILABLE` instead of transcribing) |
| `WHISPER_TIMEOUT_SECONDS`            | 900           | Timeout for audio extraction + whisper-cli combined          |
| `WHISPER_MIN_AUDIO_DURATION_SECONDS` | 15            | Videos shorter than this skip Speech-to-Text (language auto-detection is unreliable on very short clips) |

Actuator exposure: `health`, `info`, `metrics`, `prometheus` — see `application.yml`.

### Local requirements

`yt-dlp` must be installed and on `PATH` (`brew install yt-dlp` on macOS). It depends on `deno` to solve YouTube's bot-detection challenges — Homebrew installs it automatically as a dependency. `ffmpeg` must also be on `PATH` (needed by yt-dlp's audio extraction for the Speech-to-Text fallback).

For Speech-to-Text, install whisper.cpp with `brew install whisper-cpp`, download a multilingual ggml model (e.g. `ggml-base.bin`, ~140MB) from [huggingface.co/ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp/tree/main), and point `WHISPER_MODEL_PATH` at it. Bigger models (`small`, `medium`) trade latency for accuracy. Leaving `WHISPER_MODEL_PATH` empty is fine — only the no-captions fallback stops working.

## Project Structure

```text
src/main/java/io/github/rubenix/yttranscriber/
  api/                  TranscriptionController + DTOs
  application/          Use-case services, SentenceGrouper, progress reporting
  domain/
    source/             VideoMetadata, SourceProvider port
    transcription/      TranscriptSegment, TranscriptionProvider port
    translation/        TranslatedSegment, TranslationProvider port
  integration/
    http/               Shared RestClient.Builder factory (timeouts, prototype-scoped)
    process/            ExternalProcessRunner + TempWorkspace, shared by subprocess adapters
    youtube/            YtDlpSourceProvider
    transcription/      WhisperTranscriptionProvider
    translation/        DeepLTranslationProvider
  limiter/              SessionIdFilter, UsageLimiter, CapacityGuard
  exception/            Error codes, error envelope, global handler
  config/               Properties, CORS, request-id filter, .env loader
```

## Related Repository

Frontend: [yt-transcriber-web](https://github.com/rubenmtzb/yt-transcriber-web)

## Live Demo

Not deployed yet.

## License

[MIT](LICENSE)
