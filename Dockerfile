# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -q clean package -DskipTests

FROM eclipse-temurin:25-jre AS runtime

ARG TARGETARCH
# whisper.cpp's own asset naming ("x64"/"arm64") doesn't match Docker's TARGETARCH
# ("amd64"/"arm64") for the x86 case, so map it explicitly.
ARG WHISPER_RELEASE=b4938

# SourceProvider shells out to yt-dlp, which needs deno to solve YouTube's bot-detection
# challenges. The "yt-dlp" release asset is a Python zipapp (needs a system python3, unlike the
# arch-specific "yt-dlp_linux*" standalone binaries) -- python3 keeps this portable across
# architectures without juggling per-arch download URLs.
#
# ffmpeg is needed by yt-dlp's audio extraction, and whisper-cli + a ggml model for the local
# Speech-to-Text fallback (videos with no captions in any language) -- both run entirely inside
# this image, no external API, no per-request cost. whisper.cpp ships prebuilt per-arch Linux
# binaries (dynamically linked against the .so files bundled alongside it, hence LD_LIBRARY_PATH
# below) so no compiler toolchain is needed here.
#
# The "small" model (not "base"): confirmed empirically that "base" hallucinates badly on sung/
# stylized vocals -- a captionless music video is a normal case here, not an edge case.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates unzip python3 ffmpeg \
    && curl -fsSL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp \
    && curl -fsSL https://deno.land/install.sh | DENO_INSTALL=/usr/local sh \
    && WHISPER_ARCH=$([ "$TARGETARCH" = "arm64" ] && echo arm64 || echo x64) \
    && curl -fsSL "https://github.com/ggml-org/whisper.cpp/releases/download/${WHISPER_RELEASE}/whisper-bin-ubuntu-${WHISPER_ARCH}.tar.gz" -o /tmp/whisper.tar.gz \
    && mkdir -p /opt/whisper/models \
    && tar -xzf /tmp/whisper.tar.gz -C /opt/whisper --strip-components=1 \
    && rm /tmp/whisper.tar.gz \
    && curl -fsSL https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin -o /opt/whisper/models/ggml-small.bin \
    && chmod -R a+rX /opt/whisper \
    && apt-get purge -y curl unzip \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

ENV WHISPER_BINARY_PATH=/opt/whisper/whisper-cli
ENV WHISPER_MODEL_PATH=/opt/whisper/models/ggml-small.bin
ENV LD_LIBRARY_PATH=/opt/whisper

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
WORKDIR /app
COPY --from=build /workspace/target/yt-transcriber-api-*.jar app.jar
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
