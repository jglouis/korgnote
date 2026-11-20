# The sync server

The server the apps pair with and sync through. It serves the notes folder of the computer it runs
on to paired devices over TLS (port 8443), and the web app, the Devices page and the desktop app on
this computer only (port 8080). How to run it is in the [main README](../README.md#your-computer-with-the-korgnote-server);
the protocol is in [docs/05](../docs/05-sync-protocol.md).

It is written in Rust (hyper, tokio, rustls), so that it runs on computers with little memory, such
as a Raspberry Pi. It is built with Cargo, apart from the Gradle build of the apps.

## Building and running

```bash
cargo build --release --manifest-path server/Cargo.toml
NOTES_DIR=~/notes server/target/release/korgnote-server
```

The variables are listed in the [main README](../README.md#server). `-Dnotes.dir=…` and
`-Dwasm.dir=…` given as arguments win over `NOTES_DIR` and `WASM_DIR`. `KORGNOTE_LOG=debug` writes
more to the log. Started from the repository root or from `server/`, it serves the web app from
`composeApp/build/dist/wasmJs/` when that has been built.

As a container, from the repository root (the image holds the web app too):

```bash
docker build -t korgnote-server .
```

The image is the binary, static and linked against musl, and the web app, on `scratch`: no shell
and no libraries. It is built for amd64 and arm64 without emulation, the binary cross-compiled.

## What it costs to run

Measured with `docker stats` on a container holding 370 made-up notes (9.5 MB), through four rounds
of manifest, bundle, 370 downloads and 62 uploads, then left idle:

| | |
|---|---|
| Just started | 2 MiB |
| Peak during an ordinary sync | 7 MiB |
| Idle afterwards | 2.4 MiB |
| Peak sending one 20 MB note | 23 MiB |
| Image | 21.6 MB |

What it needs can be said from the code rather than from measuring: a few megabytes of its own,
a note held once while it is read or written, and at most 64 MB of notes being sent held at a time
(`UPLOAD_BUDGET_MB`); a note that would pass that waits its turn. Four threads answer, and up to
sixteen more work on the disk.

## Tests

```bash
cargo test --manifest-path server/Cargo.toml
```

These run over real connections and a real folder. `ExternalServerSyncTest`, in `composeApp`'s JVM
tests, runs the app's own sync code, the desktop app's HTTP client pinned to the server's
certificate, `KtorSyncApi`, `SyncEngine` and `DocumentStore`, against the built binary started as a
program of its own: two devices, merges, conflicts, deletions, renames, the change stream, a
restart, and a made-up day from a seed. CI runs both.

```bash
cargo build --release --manifest-path server/Cargo.toml
KORGNOTE_SERVER_UNDER_TEST=$PWD/server/target/release/korgnote-server \
    ./gradlew --no-build-cache :composeApp:jvmTest --rerun --tests '*ExternalServerSyncTest*'
```

Every note in all of these is made up by the test that uses it.

## Upgrading from 1.8 or earlier

Releases up to 1.8 shipped a server that ran on the JVM. This one reads the same variables, serves
the same ports and keeps the same state directory: a `~/.korgnote-server` (or `/state`) from an
earlier release is read as it is, its PKCS#12 `tls.jks` included, so paired devices need no pairing
again. A state directory this server makes has an EC P-256 key; a phone trusts the certificate by
its fingerprint, whatever its key. A key store in the older JKS format, which no release wrote, is
refused with what to do.

## Known limits

- A save can be announced twice on the change stream, when the watcher looks at the file between
  the save and its announcement. A client that pulls on a hint pulls twice.
- A note of up to 32 MB is held whole while it is checked and written.
- A reader that stops reading the change stream has it closed once it has lost an event, for its
  app to catch up from the manifest. How soon depends on how much the system buffers on the way.
- A notes folder that goes away, or is no longer on the volume it was on at startup, as an unmounted
  volume's mount point is, fails the listing with a `500` until it is back, rather than being listed
  as a library with no notes. A volume that was never mounted when the server started is not told
  apart from an empty folder: `KORGNOTE_REQUIRE_NOTES_DIR` is asked at startup only.
- A request body is read as UTF-8, ISO-8859-1, US-ASCII or UTF-16; another charset is `415`. The
  apps send UTF-8.
- Not yet tried by hand: iOS against it, a real phone on Wi-Fi, a phone that sleeps and comes back.
