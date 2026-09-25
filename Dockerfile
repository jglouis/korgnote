# check=skip=SecretsUsedInArgOrEnv
# (It flags KORGNOTE_REQUIRE_TOKEN by name; that variable is a switch, not a secret.)

# T23 — the sync server in a container. Build from the repository root:
#
#   docker build -t korgnote-server .
#
# docker-compose.yml shows how to run it with a notes directory from the host.

# --- Build: the server's distribution and the web client, from the same Gradle build CI uses ---
FROM eclipse-temurin:25-jdk AS build
# The Node that the Kotlin plugin downloads for the wasm build links against libatomic.
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY . .
# Gradle configures every module even though only these two tasks run. The cache mount keeps
# Gradle's downloads between builds (Node and the wasm toolchain included); it is not part of any
# image layer.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :server:installDist :composeApp:wasmJsBrowserDistribution

# --- Run: a JRE and the distribution, nothing else ---
FROM eclipse-temurin:25-jre

# Files the server creates in the mounted directory belong to this user. Match the host user that
# owns the notes (`id -u`, `id -g`), either here at build time or with `--user` / compose `user:`.
ARG UID=1000
ARG GID=1000
# The base image already has a user at 1000 (ubuntu); take its IDs over rather than fail on them.
RUN userdel --remove ubuntu 2>/dev/null || true; \
    groupadd --gid "$GID" korgnote && \
    useradd --uid "$UID" --gid "$GID" --no-create-home --shell /usr/sbin/nologin korgnote

COPY --from=build /src/server/build/install/server /opt/korgnote
# The browser client, served at / on the same origin as the API. KORGNOTE_DISABLE_WEB=1 at run time
# serves the API only.
COPY --from=build /src/composeApp/build/dist/wasmJs/productionExecutable /opt/korgnote/web

# The container is exactly where a port gets published by accident, so it refuses to start without a
# token, and a missing or unwritable /notes is fatal instead of falling back to the samples.
# SERVER_OPTS only silences the JDK's warning about Netty loading its native transport.
ENV NOTES_DIR=/notes \
    WASM_DIR=/opt/korgnote/web \
    KORGNOTE_PORT=8080 \
    KORGNOTE_REQUIRE_TOKEN=1 \
    KORGNOTE_REQUIRE_NOTES_DIR=1 \
    SERVER_OPTS=--enable-native-access=ALL-UNNAMED

USER korgnote
EXPOSE 8080
ENTRYPOINT ["/opt/korgnote/bin/server"]
