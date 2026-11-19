# The Rust sync server in a container, beside the Kotlin one's (../Dockerfile), which it takes the
# place of: the same ports, the same variables, the same /notes and /state. Build from the
# repository root, since the web client and the favicon are the Kotlin build's:
#
#   docker build -f server-rust/Dockerfile -t korgnote-server-rust .
#
# and run it as the other is run, docker-compose.yml with its `image:` changed, or:
#
#   docker run --rm --user "$(id -u):$(id -g)" -v ~/notes:/notes -v ~/.korgnote-server:/state \
#       -p 127.0.0.1:8080:8080 -p 8443:8443 -e KORGNOTE_ADVERTISED_ADDRESSES=192.168.1.20 korgnote-server-rust
#
# The state folder is the Kotlin server's, read as it is: the same server to a paired phone.

# --- The web client, from the same Gradle build CI uses ---
# On the machine's own platform, whatever the image is for: what it makes is wasm, the same on any CPU.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS web
# The Node that the Kotlin plugin downloads for the wasm build links against libatomic.
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
# What Gradle needs to configure and to build the web client, named one by one so that a change to
# the Rust server does not build the web client again.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY kotlin-js-store kotlin-js-store
COPY shared shared
COPY composeApp composeApp
COPY server server
COPY androidApp androidApp
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :composeApp:wasmJsBrowserDistribution

# --- The server: one static binary ---
# Built for the image's own platform, on it. Alpine's toolchain links against musl, which makes a
# binary that needs nothing from the image it runs in.
FROM rust:1-alpine AS server
RUN apk add --no-cache build-base
WORKDIR /src
COPY server-rust server-rust
COPY server/src/main/resources/favicon.png server/src/main/resources/favicon.png
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/src/server-rust/target \
    cargo build --release --locked --manifest-path server-rust/Cargo.toml \
    && cp server-rust/target/release/korgnote-server /korgnote-server

# --- Run: the binary and the web client, and nothing else ---
# No shell, no libraries, no package to carry a CVE: the binary is the image.
FROM scratch
COPY --from=server /korgnote-server /korgnote-server
# The browser client, served at / on the same origin as the API. KORGNOTE_DISABLE_WEB=1 at run time
# serves the API only.
COPY --from=web /src/composeApp/build/dist/wasmJs/productionExecutable /web

# As the Kotlin server's image sets them. A missing or unwritable /notes is fatal, not an empty
# library that looks healthy. The plain port listens on every interface inside, which is the only
# way a published port reaches it: publish it on the host's loopback only, since it serves this
# computer without a token. The secure port is for paired devices; /state keeps the certificate and
# the device list.
ENV NOTES_DIR=/notes \
    WASM_DIR=/web \
    KORGNOTE_PORT=8080 \
    KORGNOTE_HTTP_HOST=0.0.0.0 \
    KORGNOTE_HTTPS_PORT=8443 \
    KORGNOTE_STATE_DIR=/state \
    KORGNOTE_REQUIRE_NOTES_DIR=1

# Files the server creates in the mounted directory belong to this user. Match the host user that
# owns the notes (`id -u`, `id -g`), either here at build time or with `--user` / compose `user:`.
ARG UID=1000
ARG GID=1000
USER ${UID}:${GID}
EXPOSE 8080 8443
ENTRYPOINT ["/korgnote-server"]
