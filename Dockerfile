# The sync server in a container. Build from the repository root:
#
#   docker build -t korgnote-server .
#
# docker-compose.yml shows how to run it with a notes directory from the host.

# --- The web client, from the same Gradle build CI uses ---
# On the machine's own platform, whatever the image is for: what it makes is wasm, the same on any CPU.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS web
# The Node that the Kotlin plugin downloads for the wasm build links against libatomic.
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
# What Gradle needs to configure and to build the web client, named one by one so that a change to
# the server does not build the web client again. Gradle configures every module in
# settings.gradle.kts, Android's included, even though only the web client is built.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY kotlin-js-store kotlin-js-store
COPY shared shared
COPY composeApp composeApp
COPY androidApp androidApp
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :composeApp:wasmJsBrowserDistribution

# --- The server: one static binary ---
# Built on the machine's own platform too, cross-compiled for the image's: an image for amd64 and
# arm64 builds without emulating the other CPU. xx sets up the linker and C compiler (ring has C
# in it) for the target; Alpine's musl makes a binary that needs nothing from the image it runs in.
FROM --platform=$BUILDPLATFORM tonistiigi/xx:1.6.1 AS xx
FROM --platform=$BUILDPLATFORM rust:1-alpine AS server
COPY --from=xx / /
RUN apk add --no-cache clang lld
ARG TARGETPLATFORM
RUN xx-apk add --no-cache musl-dev gcc
WORKDIR /src
COPY server server
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,id=korgnote-server-target-$TARGETPLATFORM,target=/src/server/target \
    xx-cargo build --release --locked --manifest-path server/Cargo.toml \
    && cp "server/target/$(xx-cargo --print-target-triple)/release/korgnote-server" /korgnote-server \
    && xx-verify --static /korgnote-server

# --- Run: the binary and the web client, and nothing else ---
# No shell, no libraries, no package to carry a CVE: the binary is the image.
FROM scratch
COPY --from=server /korgnote-server /korgnote-server
# The browser client, served at / on the same origin as the API. KORGNOTE_DISABLE_WEB=1 at run time
# serves the API only.
COPY --from=web /src/composeApp/build/dist/wasmJs/productionExecutable /web

# A missing or unwritable /notes is fatal, not an empty library that looks healthy. The plain port
# listens on every interface inside, which is the only way a published port reaches it: the
# compose file publishes it on the host's loopback only, since it serves this computer without a
# token. The secure port is for paired devices; /state keeps the certificate and the device list.
ENV NOTES_DIR=/notes \
    WASM_DIR=/web \
    KORGNOTE_PORT=8080 \
    KORGNOTE_HTTP_HOST=0.0.0.0 \
    KORGNOTE_HTTPS_PORT=8443 \
    KORGNOTE_STATE_DIR=/state \
    KORGNOTE_REQUIRE_NOTES_DIR=1

# Files the server creates in the mounted directory belong to this user. Match the host user that
# owns the notes (`id -u`, `id -g`), either here at build time or with `--user` / compose `user:`.
# A bare UID:GID needs no account in the image.
ARG UID=1000
ARG GID=1000
USER ${UID}:${GID}
EXPOSE 8080 8443
ENTRYPOINT ["/korgnote-server"]
