# The sync server, in Rust

An experiment: the same server as the Kotlin one in [`server/`](../server), written again in Rust
for computers with little memory, such as a Raspberry Pi. It does not replace the Kotlin server and
nothing else in the repository depends on it. The Kotlin server and its tests are the
specification: where the two differ, this one is wrong, unless the difference is listed
[below](#where-it-differs-on-purpose).

It reads the same environment variables, serves the same two ports, and keeps the same state
directory, key store included, so one `~/.korgnote-server` serves either: to a paired phone they
are the same server, and switching between them needs no pairing again.

## What it costs to run

Measured with `docker stats` on a container holding 370 made-up notes (9.5 MB), through four rounds
of manifest, bundle, 370 downloads and 62 uploads, then left idle:

| | Kotlin, as released | Kotlin, with the JVM flags of `feature/server-memory-flags` | Rust |
|---|---|---|---|
| Just started | ~200 MiB | ~133 MiB | 2 MiB |
| Peak during an ordinary sync | 356 MiB | ~180 MiB | 7 MiB |
| Idle afterwards | 356 MiB | 148 MiB | 2.4 MiB |
| Peak sending one 20 MB note | 423 MiB | 368 MiB | 23 MiB |
| Image | 289 MB | 289 MB | 21.6 MB |

What it needs can be said from the code rather than from measuring: a few megabytes of its own,
a note held once while it is read or written, and at most 64 MB of notes being sent held at a time
(`UPLOAD_BUDGET_MB`); a note that would pass that waits its turn. Four threads answer, and up to
sixteen more work on the disk.

## Building and running

```bash
cargo build --release --manifest-path server-rust/Cargo.toml
NOTES_DIR=~/notes server-rust/target/release/korgnote-server
```

Every variable of the Kotlin server means the same here: `NOTES_DIR`, `KORGNOTE_PORT`,
`KORGNOTE_HTTP_HOST`, `KORGNOTE_HTTPS_PORT`, `KORGNOTE_ADVERTISED_PORT`,
`KORGNOTE_ADVERTISED_ADDRESSES`, `KORGNOTE_SERVER_NAME`, `KORGNOTE_STATE_DIR`, `WASM_DIR`,
`KORGNOTE_DISABLE_WEB`, `KORGNOTE_REQUIRE_NOTES_DIR`. Where the Kotlin server takes
`-Dnotes.dir=…` and `-Dwasm.dir=…`, this one takes them as arguments spelled the same.
`KORGNOTE_LOG=debug` writes more to the log.

As a container, from the repository root (the web client and the favicon are the Kotlin build's):

```bash
docker build -f server-rust/Dockerfile -t korgnote-server-rust .
```

It runs as the Kotlin image does: the same `/notes` and `/state`, the same ports, the same `--user`.
The image is built for the platform it is built on; on an Apple Silicon Mac that is `linux/arm64`,
which is what a 64-bit Raspberry Pi runs.

## How it is held to the Kotlin server

Four layers, each catching what the one before cannot.

1. **`cargo test`**: 222 tests. The Kotlin server's 144 are ported test for test where they apply,
   over real connections and a real folder; the rest cover what the comparison below brought up.
2. **`conformance/harness.py`** sends one scripted conversation to both servers and compares every
   answer, and what each leaves on disk: about 2,300 requests that probe paths, methods, headers,
   compression, hosts and origins, pairing, the change stream and the watcher, then a seeded random
   sequence of saves and deletions. It also has the Rust server redraw every QR code the Kotlin one
   drew, and hands one state directory from each server to the other and back. It runs on macOS,
   and on Linux from `conformance/Dockerfile`.

   ```bash
   ./gradlew :server:installDist
   cargo build --release --manifest-path server-rust/Cargo.toml
   python3 server-rust/conformance/harness.py run --seed 1 --steps 1500
   ```

   `conformance/diff.py` and `conformance/view.py` read the transcripts it leaves.
3. **`ExternalServerSyncTest`** (in `composeApp`'s JVM tests) runs the app's own sync code, the
   desktop app's HTTP client pinned to the server's certificate, `KtorSyncApi`, `SyncEngine` and
   `DocumentStore`, against a server started as a program of its own: two devices, merges,
   conflicts, deletions, renames, the change stream, a restart, and a made-up day from a seed.

   ```bash
   KORGNOTE_SERVER_UNDER_TEST=$PWD/server-rust/target/release/korgnote-server \
       ./gradlew --no-build-cache :composeApp:jvmTest --rerun --tests '*ExternalServerSyncTest*'
   ```

   The same command with `server/build/install/server/bin/server` runs it against the Kotlin
   server, which is how the test itself is known to be right.
4. **The Android app on an emulator**, paired with the Rust server over TLS. See
   [what was checked by hand](#what-was-checked-by-hand).

Every note in all of these is made up by the test that uses it.

## Where it differs on purpose

Each of these is listed in `ALLOWED` in the harness with the steps it excuses.

- **A notes folder that is a link.** The Kotlin server walks it without following the link, lists
  a complete, empty library, which takes every note off every phone, and never notices an edit.
  This one lists and watches the folder the link leads to.
- **An `Origin` it cannot read.** Ktor's `Url` makes `localhost` on port 80 of anything it cannot
  parse, `null` included, so the Kotlin server takes such an `Origin` for its own page when it is
  itself asked for as `localhost` on port 80. This one reads an `Origin` strictly. No browser writes
  one any other way, so nothing of the server's own is refused.
- **Charsets.** The Kotlin server reads a body in any charset Java knows. This one reads UTF-8,
  ISO-8859-1, US-ASCII and UTF-16, and answers `415` for another. The apps send UTF-8.
- **What a `500` says.** Where the disk will not say what a note is, the Kotlin server answers with
  the exception's message, which is the file's path on the server's computer. This one answers
  without it.
- **An edit made while the folders are being watched again.** The Kotlin server leaves it to its
  one-minute walk. This one walks once more as soon as the watches are in place.
- **Several deletions found by one walk** are announced in the order of their names; the Kotlin
  server announces them in the order of a hash table.
- **A save that stops coming.** A body that says nothing for a minute is given up on with `408`,
  and a connection whose other end has gone is closed by the system's keep-alive probes. The Kotlin
  server waits for both for ever.
- **A pairing link too long for a QR code** is a `500` from both; this one's says to give fewer
  addresses, where the Kotlin one's says how many bits the library was short.

And three that cannot be seen from a client that behaves:

- A `304` has no `Content-Length: 0` (hyper leaves it out).
- A request line with bytes that are not ASCII is `400`, where Netty passes it on to find nothing
  (`404`). The apps percent-encode.
- A pairing request must be JSON as JSON is written. Ktor's lenient parser would also take
  unquoted strings.

## What both servers do, for better or worse

Kept as it is in the Kotlin server, because it is the specification:

- A save can be announced twice on the change stream, when the watcher looks at the file between
  the save and its announcement. A client that pulls on a hint pulls twice.
- A note of up to 32 MB is held whole while it is checked and written. The Rust server holds it
  once, and the Kotlin one about three times.
- A reader that stops reading the change stream has it closed once it has lost an event, for its
  app to catch up from the manifest. How soon depends on how much the system buffers on the way.
- A notes folder that is there but empty, as the folder a volume is mounted on is while the volume
  is not, is a complete library with no notes in it. `KORGNOTE_REQUIRE_NOTES_DIR` is asked at
  startup only.

## What a second pair of eyes found

The code was reviewed once by a reviewer given the Kotlin server as the specification and none of
the reasoning behind the port. It found no way to lose or overwrite a note, to leave one out of a
complete manifest, or to reach what should not be reached. It did find ways the server could stop
or hang where the Kotlin one goes on, all since fixed, each with a test:

- a save whose sender vanished held its share of the upload budget for good;
- a log line that could not be written, to a full disk or a closed pipe, ended the server;
- so did a pairing link too long for a QR code, and a listing of folders some thousands deep;
- on Linux, a notes volume unmounted and mounted again left the watcher watching nothing;
- a note named `.org` and nothing more was not heard by the watcher;
- broken UTF-16 was read as Rust reads it, not as Java does.

## What was checked by hand

On 2026-11-19, the Android debug app built from this branch, on a throwaway emulator (API 34), with
the Rust server on the same computer serving twelve made-up notes:

- It paired from the server's `korgnote://pair` link, over TLS to the server's certificate.
- Its first sync asked for the manifest and then the bundle, and showed the notes.
- The tour note it added, and text typed into it afterwards, arrived in the server's folder.
- A note edited, one added and one deleted in the server's folder were fetched by the app within a
  second, through the change stream, and the note on screen was redrawn with the edit.
- The Devices page listed the emulator with when it was last seen.

The server was at 9.6 MB of memory on macOS after all that.

Not checked by hand: a real phone on Wi-Fi, a phone that sleeps and comes back, and iOS.

## What is not done

- Nothing publishes the image: it is not in CI, the release script or `docker-compose.yml`.
- An image for another platform than the one it is built on has not been built.
- iOS has not been tried against it. The server's side of TLS is rustls, with TLS 1.2 and 1.3 and
  HTTP/2, which the JVM and Android clients were tested with.
