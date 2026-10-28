# T23 — the sync server in a container. Build from the repository root:
#
#   docker build -t korgnote-server .
#
# docker-compose.yml shows how to run it with a notes directory from the host.

# --- Build: the server's distribution and the web client, from the same Gradle build CI uses ---
# On the machine's own platform, whatever the image is for: what it makes is bytecode and wasm, the
# same on any CPU (Netty's QUIC natives come for every platform, and its epoll jar carries none). So
# an image for amd64 and arm64 builds once, without emulating the other CPU, and only the stage
# below, which copies files and runs nothing, is made for each.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk AS build
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
# Distroless: the same Temurin 25 JRE without a shell or package manager, so far fewer packages for
# CVEs to land in. Security fixes arrive by rebuilding on a newer base. For a shell to debug with,
# swap in the :debug-nonroot tag (busybox).
FROM gcr.io/distroless/java25-debian13:nonroot

COPY --from=build /src/server/build/install/server /opt/korgnote
# The browser client, served at / on the same origin as the API. KORGNOTE_DISABLE_WEB=1 at run time
# serves the API only.
COPY --from=build /src/composeApp/build/dist/wasmJs/productionExecutable /opt/korgnote/web

# A missing or unwritable /notes is fatal instead of falling back to the samples. The plain port
# listens on every interface inside, which is the only way a published port reaches it: the
# compose file publishes it on the host's loopback only, since it serves this computer without a
# token. The secure port is for paired devices; /state keeps the certificate and the device list.
ENV NOTES_DIR=/notes \
    WASM_DIR=/opt/korgnote/web \
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
# Ktor resolves the working directory at startup, and the base image's (/home/nonroot) is closed to
# any UID but 65532.
WORKDIR /opt/korgnote
EXPOSE 8080 8443
# There is no /bin/sh for Gradle's bin/server script, so start the JVM directly with what that script
# would pass: the development flag from server/build.gradle.kts, and native access for Netty's
# transport (which only silences the JDK's warning).
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-Dio.ktor.development=false", \
            "-cp", "/opt/korgnote/lib/*", "com.korgnote.server.ApplicationKt"]
