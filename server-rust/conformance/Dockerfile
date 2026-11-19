# Where the comparison of the two servers runs on Linux, which is where they are deployed: a JDK
# for the Kotlin server, and Python and curl for the harness. The servers themselves are mounted in.
#
#   ./gradlew :server:installDist
#   docker build -t korgnote-conformance server-rust/conformance
#   docker run --rm -v "$PWD":/src:ro -v /path/to/linux/korgnote-server:/korgnote-server:ro \
#       -e KORGNOTE_RUST_SERVER=/korgnote-server --user "$(id -u):$(id -g)" -e HOME=/tmp \
#       korgnote-conformance python3 /src/server-rust/conformance/harness.py run --work /tmp/work
#
# Not as root: the harness takes permissions away from folders to see what each server says then,
# and nothing is out of root's reach.
FROM eclipse-temurin:25-jdk
RUN apt-get update && apt-get install -y --no-install-recommends python3 curl && rm -rf /var/lib/apt/lists/*
