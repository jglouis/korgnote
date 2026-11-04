# 05 — Local store and sync protocol

Addresses G4, G5 and defects D1, D2, D3, D8, D9, D10, D11.

## Server: a file store that tells the truth

The server answers from the disk on every request (`server/…/FileStore.kt`). It once served a snapshot
taken at startup, which could overwrite edits made in Emacs (D1).

```kotlin
class FileStore(val root: Path, val readOnly: Boolean = false) {
    fun list(): List<FileMeta>                      // walks the tree on demand
    fun readWithMeta(path: String): FileContent?    // a file's text and its version, from one read
    fun meta(path: String): FileMeta?
    fun put(path: String, content: String, ifMatch: String?, mustNotExist: Boolean): WriteResult
    fun delete(path: String, ifMatch: String?): WriteResult
}

data class FileMeta(val path: String, val size: Long, val mtimeMillis: Long, val etag: String)
```

- `etag` is a **content hash** (SHA-256, hex-truncated to 16 bytes), not an mtime: Emacs and
  Syncthing rewrite mtimes freely, and a hash makes "did this change?" exact.
- Hashes are cached by `(path, size, mtime)` so a manifest request rehashes only what moved. A file
  changed in the last two seconds is hashed again next time: where timestamps are coarse, an edit
  moments later can keep its size and its time, and a stale etag would pass `If-Match`.
- `put` is atomic: write to a temporary file in the same directory, then rename. A crash or a
  dying connection never truncates a note. There is no `fsync` before the rename, so a power cut can
  still lose a save the filesystem had not flushed.
- The `If-Match` check and the move are under one lock, so of two devices pushing from the same version
  only one wins and the other is told to merge. An editor outside the server does not take it: a save
  landing in that instant is overwritten, which nothing on a plain filesystem can prevent.
- A text and its etag come from one read (`readWithMeta`): read apart, an edit landing between pairs the
  old etag with the new text, and the client's next push is a false conflict.

**What is a note.** `isLibraryPath` is one rule for what is listed, what can be read and written, and
what is watched: an `.org` file, in no dot-folder. `.korgnote-trash` holds what was deleted, `.stversions`
Syncthing's old versions and `.git` git's own business; served as notes, those would reach every phone
as more copies of the same headings. On top of it, every call rejects absolute paths, empty, `.` and `..`
segments, and anything that resolves outside `root` after symlink resolution — for a new file, the nearest
folder that exists, so a note cannot be created through a folder linked in from elsewhere. Files are
capped at 32 MB. `list` goes through the same check, so a symlink leading outside is not listed: it
could not be fetched, and a phone would keep trying.

A path that leaves the manifest this way is, to a phone, a note deleted on the server. A clean copy goes
at the next complete manifest. A copy with an unsent edit is kept and pushed as a new note, which the
server refuses with `422`, so the push fails each cycle, and says nothing, while the edit stays in the
replica. A dot-*file* is not covered by the rule; see [open gaps](08-roadmap.md#open-and-known-gaps).

**With no notes folder**, `ServerConfig.openStore` serves an empty, read-only, unwatched library, and says
so in the log and on the Devices page. A container sets `KORGNOTE_REQUIRE_NOTES_DIR`, which stops the
server instead.

**Watching** is a class of its own, `server/…/NotesWatcher.kt` (a `java.nio` `WatchService`), which turns
each change on disk into a journal entry. Anything that moves folders, an overflow of events or an
invalid key makes it build its watches again from scratch and walk the tree, journaling every difference:
a watch follows its folder's inode, so a renamed folder is its old notes deleted and its new ones made.
It walks the tree once a minute as well (`rescanEvery`), because a watch can miss events and say nothing:
Docker Desktop's file sharing drops some made on the host. What that walk finds, and no event reported
within ten seconds, is logged as a warning and counted rather than healed quietly.

How it is doing is `WatcherStatus`, shown under *Notes folder* on the Devices page: the notes and folders
watched and when they were last checked, with a warning when watching fails, when the checks stop, or
when an edit was found that no file event reported. A phone told "nothing changed" by a server that
stopped looking reports a good sync, so this is the only place to notice.

## Protocol

Versioned under `/api/v1`. All responses are compressible (Ktor's `Compression` plugin; Org text gzips
roughly 4–5×, D10).

### `GET /api/v1/manifest[?since=<generation>]`

```json
{
  "generation": 1758300000421,
  "complete": true,
  "entries": [
    { "path": "inbox.org",          "size": 2310,  "mtime": 1758300000000, "etag": "9f2a…" },
    { "path": "projects/work.org",  "size": 18422, "mtime": 1758299100000, "etag": "41bc…" }
  ],
  "deleted": []
}
```

The client diffs this against its own metadata. With `since`, the server answers from an
in-memory journal fed by the file watcher and sets `complete: false`; if the generation is
unknown (server restarted, journal trimmed) it returns a full manifest with
`complete: true`. The client handles both, which keeps the journal a pure optimisation. The classes are
shared by server and apps (`Manifest`, `ManifestEntry`). A full manifest's generation is read before the
walk, so a change landing during it is reported again rather than lost.
A delta names each path once, as its latest change, so a note deleted and made again is listed
as present. A delta names only what moved on the server, so the client pushes its dirty and
never-synced files even when the delta leaves them out: absence means "unchanged remotely".

For a restart to be noticed, a new run must never hand out a generation an old one did. The
counter therefore starts at the time the server started, in milliseconds, not at zero: a run
counting from zero would soon pass the number a phone kept and answer it with only the changes
after it, and the phone would never see the ones in between. The client keeps the generation a
manifest gives as it is, even when lower, so a server whose clock went back is still asked from
where it is.

Size check: ~120 bytes per entry, so 1,000 files ≈ 120 KB raw, ~20 KB gzipped, and that is
the *worst* case (a cold client). The steady state is a `since` query returning nothing.

### `GET /api/v1/files/{path...}`

Request `If-None-Match: <etag>` → `304 Not Modified` with no body, or `200` with the raw
bytes, `ETag`, and `Content-Type: text/plain; charset=utf-8`; `404` when there is no such
file. The app never sends `If-None-Match` here: it only fetches what the manifest says moved. To the
client a `404` is `FetchResult.Gone`, deleted since it was listed, and the next manifest will say so;
anything else that brings no file is `FetchResult.Failed`, which may be a file that exists, and of which
nothing can be said ([below](#sync-algorithm)).

ETags go out unquoted (`ETag: 9f2a…`); the server strips quotes from the ones it is sent.

### `GET /api/v1/bundle[?since=<generation>]`

One request, many files — one JSON object per line, served as `application/json`, gzipped:

```
{"path":"inbox.org","etag":"9f2a…","text":"* TODO …"}
{"path":"projects/work.org","etag":"41bc…","text":"…"}
```

For any client that starts empty and wants the whole corpus: the web app on every page load, and a
freshly installed native client on first sync. Without it, a cold start is N `GET`s, which is a poor
trade even over HTTP/2. With `since`, it returns only
what changed — making it equally useful as a catch-up after a device has been offline for a
while. The engine asks for it only when a cycle has eight or more files to pull: one at a time,
a first sync of 371 notes took about 80 s on a phone, and about 15 s with the bundle.

The response is streamed rather than built in memory, and capped: beyond 8 MB of text it
ends with a `{"truncated":true,"generation":…}` line. The client does not page with `since`;
it fetches what the bundle left out one by one.

### `PUT /api/v1/files/{path...}`

Body is the raw file content. Headers:

| Header | Meaning |
|---|---|
| `If-Match: <etag>` | "I am editing the version with this hash." → `409 Conflict` if the server has moved on |
| `If-None-Match: *` | "Create only — fail if it exists." (new notes, capture) |
| neither | Unconditional: create or replace. The server accepts it; the app cannot ask for it, as `PushCondition` has only the two above. |

Responses: `200 {"etag": "…"}`, `409` with the current `ETag` so the client can fetch and
merge, `404` for `If-Match` on a file that is not there, `403` when the library is read-only (no notes
folder), `413` over size limit, `422` invalid path, and `500` when the disk refuses the write. That body
names the note but not where the notes are on this computer; the cause goes to the server's log.

> `409` rather than `412` is the pragmatic choice: this is a *mergeable* conflict, not a
> failed precondition the client should give up on.

### `DELETE /api/v1/files/{path...}`

`If-Match` required — `422` without it. `204` on success, `409` with the current `ETag` when
the file moved, `404` when it is already gone, and `403` and `500` as for a `PUT`. The server moves the
file to a trash directory (`.korgnote-trash/<date>/…`) rather than unlinking: cheap insurance for a sync
bug.

### `GET /api/v1/events` (SSE)

```
event: changed
data: {"generation":1758300000422,"path":"inbox.org","etag":"7b31…","deleted":false}

event: deleted
data: {"generation":1758300000423,"path":"old.org","etag":null,"deleted":true}
```

Fed by the file watcher, so changes made in Emacs or delivered by Syncthing reach the
clients within seconds. SSE over WebSocket because it is one-directional, survives proxies,
reconnects natively, and needs no extra dependency. Clients treat it as a *hint*: correctness always
comes from the manifest diff. For the same reason a client that stops reading loses the oldest events
rather than holding up the writer, and catches up from the manifest.

A quiet stream sends a `: ping` comment every 30 s. A connection can die without either end
hearing of it (a phone asleep, or moved to another network), and then looks exactly like one with
nothing to say; a client that has seen a heartbeat takes 90 s of silence as a dead connection. A
stream lost after it opened is reported as such, never retried at another of the server's
addresses: the client reconnects and runs a full cycle, since the stream missed whatever changed
meanwhile. Coming back to the app runs one too, for the same reason.

### Auth

The first design was one shared token: `Authorization: Bearer <token>`, read by the server
from `KORGNOTE_TOKEN`, with TLS left to a reverse proxy. It was replaced by pairing: each device
gets a token of its own, which the Devices page can revoke, and TLS is built in, trusted by the
certificate's fingerprint, so a phone needs no proxy and no certificate authority.
`KORGNOTE_TOKEN` is now only logged as an ignored leftover. The server
(`server/…/ServerSecurity.kt`) has two ports:

| Port | For | Who gets in |
|---|---|---|
| 8443, TLS (`KORGNOTE_HTTPS_PORT`) | paired devices | `/api/v1/`, with `Authorization: Bearer <device token>`, and `/api/pair`, which is how a device gets one. Everything else is `404`, however the path is spelled: what may be reached is checked against the path as it was sent, while routing ignores empty segments and decodes the rest. An unknown or revoked token is `401`. |
| 8080, plain (`KORGNOTE_PORT`) | this computer | no token. Bound to `127.0.0.1` (`KORGNOTE_HTTP_HOST`). The connection must come from loopback — or, in a container, from its network's gateway, which is where the host's published port arrives — or `403`. The `Host` must also name this computer (`localhost` or a loopback address), or `403` — which stops DNS rebinding — and a change that carries an `Origin` must come from the server's own pages. Serves the web client, the desktop app, the Devices page (`/devices`) and `POST /api/local/pairing`, which `scripts/pair.sh` calls. |

TLS uses a self-signed certificate the server makes on first start and keeps, with its id and
the paired devices, in `KORGNOTE_STATE_DIR` (`~/.korgnote-server`). The Devices page shows a QR
code holding the server's id, its addresses (`KORGNOTE_ADVERTISED_ADDRESSES`,
`KORGNOTE_ADVERTISED_PORT` for a container), the certificate's SHA-256 and a one-time code. The
device trades the code at `POST /api/pair` for a token of its own, over a client that trusts
that certificate and nothing else (`shared/…/pairing/PinnedTrustManager.kt`); the server keeps
only a hash of the token.

What this trusts, stated plainly:

- **Any process on this computer has full read and write through port 8080**, and can mint
  pairing codes. It could read the notes folder anyway; a web page cannot, for the `Host` and
  `Origin` checks.
- The port trusts where a connection comes from, not what it says: a `Host: localhost` header is
  the caller's to write. In Docker the plain port listens on `0.0.0.0` inside the container
  (`KORGNOTE_HTTP_HOST=0.0.0.0`), so another container on the same network could reach it and
  claim to be localhost; it is refused, because it connects from its own address. The host's
  published port arrives from the network's gateway (read from `/proc/net/route`), which is
  trusted — so 8080 must stay published on the host's loopback only (`-p 127.0.0.1:8080:8080`), or
  the network reaches it through that gateway too.
- The device token is sealed on Android (a Keystore key) and iOS (the Keychain). On the desktop it
  is sealed in Java `Preferences` with an AES key kept in the system's keyring — macOS Keychain,
  Windows Credential Manager, Secret Service on Linux (`KeyringCipher.kt`, through
  `java-keyring`). Where no keyring answers, it is kept in the clear, as before. A desktop using
  the server on its own computer has no token at all: it uses the plain port.

## Client: the local replica

```
<app-data>/
  notes/                          the replica — real .org files, the app's working copy
    inbox.org
    projects/work.org
  .korgnote/
    base/                         last-synced content, same tree shape (merge ancestor)
      inbox.org
    meta.json                     the generation, and per path: etag, dirty, deleted
```

Keeping `base/` as real files doubles disk usage of a few megabytes of text and buys
offline three-way merges — the alternative (fetching the ancestor at conflict time) fails
exactly when you are offline, which is when conflicts get created.

The replica lives in the app's own storage on every platform; it is never a user-visible
folder. Keeping notes in such a folder is a separate source, below.

`DocumentStore` owns this directory and is the only writer. It exposes documents and marks them dirty
on save. Files already on devices may also hold `size`, `mtime` and `lastSyncedGeneration` in `meta.json`,
which nothing read: they decode, and go the next time the file is written.

Access goes through `ReplicaStorage`, which is also what lets the store be tested without touching a
disk: `FileSystemReplicaStorage` is `java.io` on the JVM and Android and `NSFileManager` on iOS, written
atomically on both, and `InMemoryReplicaStorage` serves the web and the tests.

What keeps a write from landing on another, all in `DocumentStore`, under its one lock:

- **A save over a note on record writes the record first.** A crash between the two writes then leaves
  the old text marked dirty, which is pushed as an identical write; the other way round leaves new text
  marked clean, which the next pull overwrites. With no text there yet, the file goes first: a file with
  no record is pushed as a new note, where a dirty record of no text would refuse the server's note of
  that name for ever.
- **A save during a pull stays dirty, and the fetched text is dropped.** Text that took a round trip goes
  through `acceptIfUntouched`, which writes only while the path is as it was when the server was asked;
  the next cycle finds the edit meeting the server's change, and merges them. `markPushed` re-reads the
  note, so an edit made while a push was in flight stays dirty too.
- **A bundle is taken under one lock with one metadata write** (`acceptAllUntouched`), leaving out any
  file with local work. One write for each is what made a first sync quadratic. A write that fails partway
  still records those before it.

`meta.json` is decoded once and kept (`DecodedMeta`) until its modification time or size moves. The engine
asks for one file's record hundreds of times a cycle, and decoding it for each made a cycle quadratic in
the notes held. It is kept under the storage rather than in the store, since a folder's storage keeps no
metadata at all; and the file stays the truth, so a second copy of the desktop app on the same replica is
seen as soon as it writes.

### Three places for the notes

The server is optional per device. On Android and iOS the first start offers three sources
(`composeApp/…/data/NotesSource.kt`); desktop and web use the server only. Each has a store of
its own (`composeApp/…/di/di.kt`), so nothing one pulls can land in another.

| Source | What it is |
|---|---|
| Server | The replica above, `SyncEngine`, and this protocol. |
| Folder | A folder the user chose — on Android through the Storage Access Framework, on iOS a bookmarked URL — meant to be kept in step by Syncthing, Dropbox or iCloud Drive, or by nothing. `shared/…/replica/FolderNoteStorage.kt` sits under the same `DocumentStore`, but there is no base, no `meta.json` and no `SyncEngine`: saves go straight to the folder, and nothing of KorgNote's is written there. It looks again when the app comes to the front and every 10 s while it is there. A save over a file whose modification time or size moved since it was read goes to a `.sync-conflict-` copy, which the usual banner settles. |
| WebDAV (experimental) | `composeApp/…/data/WebDavSyncApi.kt`, a second `SyncApi` under the same `SyncEngine`, with a replica of its own. WebDAV has no generation, so every manifest is complete with generation 0; no bundle, so files are fetched one by one; no change stream, so `events()` waits 30 s and ends, which makes it a 30 s poll; and a delete really deletes, with no trash. Before a server is used, the app checks it refuses a write or a delete over a stale etag and a creation over an existing file. |

The folder lets a phone keep its notes where another app already syncs them, with no KorgNote
server. It is a source of its own rather than the replica pointed at a visible folder: the
folder is the notes, and nothing the engine pulls, nor its base copies and metadata, can land
in it.

### Web: the same store, backed by memory

The web build is always served by this same server, so it is online by definition. It uses
`InMemoryReplicaStorage` and the identical `DocumentStore`/`SyncEngine`.

Since pairing, it works only on the server's own computer: the TLS port serves nothing outside
`/api/`, and the plain port refuses a `Host` that does not name this computer, so a reverse
proxy passing on its own name gets `403`. A browser cannot pair (`canPair` is false on wasm).

| | Native | Web |
|---|---|---|
| Initial load | manifest diff, fetch stale files, cached across restarts | manifest on page load, then `GET /api/v1/bundle` once eight or more files are to pull |
| Save | write locally (always succeeds), push at once | the same, into memory; document stays dirty and retries if the push fails |
| Live updates | SSE → targeted pull | SSE → targeted pull (same code, same reconnect loop) |
| Base copies | on disk, survive restarts | in memory, lost on reload |
| Conflicts | three-way merge | three-way merge while the base is live; after a reload, fall back to a conflict copy |
| Notifications, background sync | real | no-op |

The only behavioural difference the user can perceive is that a browser reload starts from
the server — which, since every save is pushed at once, means losing only an edit still open
and not yet saved. A `localStorage` draft per path would close even that gap; it is not built.
[03](03-target-architecture.md#the-web-target) says why the web stays a full client.

## Sync algorithm

Per path, compare three versions: **L** local, **B** base (last synced), **R** remote
(manifest entry). One cycle runs at a time (`shared/…/replica/SyncEngine.kt`): a save, the
change stream and a retry each start one, and two running together sent the same pending
deletion three times. One that waits finds the work done.

What the server is asked is typed. A write says what it must find (`PushCondition`: `Matches` an etag, or
`Absent`), so there is no unconditional push to make by mistake. A fetch is `FetchResult`: `Fetched`,
`Gone` or `Failed`. **A file that fails to fetch keeps the generation where it was**, or the next delta
would not list it and it would stay stale until a complete manifest. Nothing else waits for it: every
other file goes through, so one the server never serves costs longer deltas, at worst a complete manifest
each time, and nothing more. A fetch that finds a file `Gone` moves on, since the next manifest says so.

| L vs B | R vs B | Action |
|---|---|---|
| same | same | nothing |
| same | changed | **pull**: fetch R, write to `notes/` and `base/` |
| changed | same | **push**: `PUT` with `If-Match: B.etag`; on success update `base/` |
| changed | changed, `L == R` | converged independently: should update `base/` only. **Known gap:** the merge returns L as merged and the engine `PUT`s it again with `If-Match: R.etag`, an identical write that bumps the generation. |
| changed | changed, `L != R` | **merge** (below) |
| deleted locally | same | `DELETE` with `If-Match` |
| deleted locally | changed | forget the deletion and pull R — never discard an edit |
| deleted locally | deleted remotely | forget the deletion |
| same | deleted remotely | delete locally |
| changed | deleted remotely | keep local, re-push as a new file — never discard an edit |
| created locally | absent | `PUT` with `If-None-Match: *` |
| created locally | created remotely | R takes the path; L is kept beside it as a conflict copy |
| present, no record of B | present | as above, unless L and R are identical. A corrupt `meta.json` is read as empty, and pulling R over every note then took any edit not yet pushed. |

### Creating, renaming and deleting (T20)

A **local deletion is a tombstone**: `FileMeta.deleted`, with the working copy and base gone and the
etag the file was deleted at kept. Without it the next complete manifest would list the file and the
client would take it straight back. The engine sends `DELETE` with that etag, so the server deletes
only the version the user saw; a `409` means it was edited elsewhere, and the edit wins — the
deletion is dropped and the file pulled. A file the server never had is simply forgotten.

A **new file is pushed with `If-None-Match: *`**, never unconditionally: a note made here must not
replace one made at the same path elsewhere. On `409` the server's file takes the path and ours is
written beside it as a conflict copy, as for a content conflict.

A **rename is a create and a tombstone**, made under the store's lock so the replica never holds one
without the other. The engine sends every deletion after every push, so another device may briefly
see both names, but never neither. The content travels with its unsynced edits, if it had any.

Paths are percent-encoded per segment on the wire, so a name with a space, `#` or `?` addresses the
note it names.

### Merging

`shared/sync/merge`: a line-based three-way merge (diff3) over `B`, `L`, `R`.

- Clean merge → write the result locally, `PUT` it with `If-Match: R.etag`, update base.
  If the note was saved again while `R` was being fetched, nothing is written and the next
  cycle merges with that save too.
- Conflicting hunks → conflict markers are **never** written into a note. **The server is the
  source of truth**: `R` keeps the path, in `notes/` and `base/`, and `L` is written beside it
  as `name.sync-conflict-20260920-101500.org`, a new note the same cycle pushes, so that settling the
  conflict at once leaves the copy in the server's trash rather than deleting the only one. A file in the
  notes directory is therefore never replaced by a version nobody merged — Emacs never sees its
  file rewritten under it — and both conflict cases, edited and created, follow one rule. The
  naming deliberately mirrors Syncthing's, which anyone using it already knows how to deal with; a second
  copy in the same second gets `-2`, `-3`.
- A `PUT` refused with `409` after the manifest said "unchanged" — a save landing on the server
  between the two — is merged exactly like the row above, never retried blindly next cycle.
- No base, no merge. A note created here, or over the name of one deleted here, has no base;
  merged against an empty one, both whole files were kept one after the other — one note with
  two IDs and two titles. R takes the path and L becomes a conflict copy instead.

### Conflicts in the app

A note is **in conflict while a `.sync-conflict-` copy sits beside it** — read off the paths
(`conflictsIn`), not stored as a flag. That holds across restarts and devices, covers
Syncthing's copies (`…-HHmmss-DEVICE.org`) too, and needs no bookkeeping: deleting the copy is
what resolves it. A copy whose note is gone is just a note.

Conflicts are shown per file, not per heading: the copy is a whole file, and one made elsewhere
carries no heading information. Headings come in when comparing, and so does settling it piece by
piece.

- **Top bar**: a warning with the count of notes in conflict; a tap lists them.
- **File tree**: the note is flagged, and each copy is drawn under it, newest first.
- **Banner** on the note or a copy: which version is on screen, and *Keep this one* (delete the
  copy, which the server moves to its trash), *Use the other* (the copy's text replaces the
  note, pushed as an edit of the server's version, then the copy is deleted) and *Compare*.
- **Compare**: the note and the copy cut into chunks, each a run of lines that differ between
  lines both share (`shared/…/sync/ConflictChunks.kt`), shown under the heading it falls in with a
  little context. Each chunk is settled on its own, as `git add -p` would: the note's lines, the
  copy's, both (the note's first), or lines written by hand in their place; what a choice leaves
  out is struck through, and *All the note's* / *All the copy's* choose every chunk at once.
  *Apply* needs a choice for every chunk. The result becomes the note, pushed as an edit of the
  server's version, and the copy is deleted; when it is the note unchanged, only the copy goes.
  The choices were made on two texts, so the result is written only while the note and the copy
  still hold them (`DocumentStore.resolveConflict`); a sync landing meanwhile brings new chunks
  and asks for the choices again.

  Chunks, not single lines: the lines of one run are usually one edit, and taking half of it is
  how an Org file ends up with a heading without its body, half a drawer, or a block that never
  ends. The hand-written lines cover anything finer.

  The comparison is two-way, the note against the copy, with no ancestor: the base is gone once
  the copy is written, and a copy made elsewhere — Syncthing's, the folder's — never had one. So a
  chunk says where the two differ, not which side changed it: a change only one side made, which
  a three-way merge would have taken without asking, is shown like the rest.

When a sync ends in a conflict the message line says so, since the open note's text changes
under the reader; and any sync that moved the replica redraws it, a save's included: left on
screen, the old text would be written back by the next save. An element open in the editor when
a sync lands is not replaced under the caret: the newer note is set aside, and saving makes the
edit on it, merged line by line; only a change to the same lines is refused
(`composeApp/…/viewmodel/ElementEditor.kt`).

### A change landing on what came in

A change is made on the text the person was looking at, since that is what its offsets mean, and the
replica may have moved past that text: a sync pulled an edit from Emacs, or a background worker filed a
capture, and the notes have not been read again yet. So `NoteChanges` merges a change with what the
replica holds by the time it lands, one write at a time, never over it. Written over it, and pushed with
the server's newest etag, it would silently take back whatever had come in.

What cannot be merged depends on what it was:

- **Typed text** goes to a `.sync-conflict-` copy beside the note, as a sync does with a conflict, since
  words cannot simply be made again.
- **A one-tap change** (a tick, a keyword, a priority, a date moved) is refused with a message, and what
  the replica holds is shown. It can simply be made again.
- **A note deleted under the change.** A save brings the note back, since it holds what was typed, and
  says so. A tick cannot: there is nothing to tick.
- **A save with nothing read to merge with**, the placeholder that stands in for a note until its text
  arrives, never replaces the note: what was typed goes beside it, as a conflict copy.

A tick or a keyword is refused outright while a heading is being added, which is on screen and nowhere
else: written then, the empty heading would reach the replica.

Org files merge well in practice: edits usually land under different headings, and a
line-level diff3 resolves those without help. ~200 lines of pure Kotlin, and the most
test-friendly component in the system.

### Triggers

What is built (`composeApp/…/viewmodel/ServerSync.kt`):

| Trigger | Behaviour |
|---|---|
| App start | full sync cycle |
| Coming back to the app | full cycle, and the change stream is woken out of its backoff: changes made elsewhere are most likely waiting then |
| SSE `changed` / `deleted` event | targeted pull or removal of that path. A burst is shown as one: the first change at once, and those that come while that is shown or within 300 ms of it together, rereading only the paths they name (`ServerSync.followStream`, `NoteLibrary.reload`) |
| Change stream lost or reconnected | full cycle, since the stream missed whatever changed meanwhile; reconnects back off from 1 s to 30 s |
| Change stream silent 90 s after a heartbeat | taken as dead: reconnect and full cycle |
| Local save | mark dirty, and run a cycle at once, which pushes it — no debounce |
| Manual retry | full cycle |
| A push that fails | asks for a sync once the network is back, the app open or not (`BackgroundSync`) |
| Android, every hour, the app closed or not | full cycle, then the widgets and the reminders from the notes as they now are |

On Android the last two are `WorkManager` jobs (`androidApp/…/sync/BackgroundSyncWorker.kt`),
running what a widget's sync button runs (`syncInBackground`, `widget/WidgetSync.kt`). Hourly
needs no network: offline it still plans reminders, which moves their window on. The one after a
failed push waits for a network, and tries again with a backoff, up to five times, while the
server cannot be reached and an edit is still waiting. Without them a meeting typed in on the
computer got no reminder on the phone until the app was opened, and an edit made offline stayed on
the phone until then too. Checked on a phone with the app closed: the edit reached the server
seconds after it came back, and the new meeting's reminder was set.

Not built, on purpose: a periodic manifest poll while in the foreground. The change stream, its
heartbeat watchdog and the catch-up after each reconnection already cover it. iOS's `BGAppRefreshTask`,
which iOS runs when it chooses if at all, is not built either.

iOS does not give an app daemon-like background sync, so the design does not count on one: a foreground
sync is fast, reminders are scheduled locally in advance ([07](07-calendar-agenda.md#notifications)), and
nothing user-visible depends on a background wake-up.

## Deferred: delta sync

Whole-file transfer of gzipped text is inside budget, so deltas are not built. If measurement ever
justifies them, the hook is: `PUT` accepts
`Content-Type: application/vnd.korgnote.patch` with `If-Match` naming the base version, body
being a unified diff produced by the same diff engine the merge uses. Server applies it,
verifies the resulting hash matches a client-supplied `X-Result-Etag`, and falls back to
requesting a full `PUT` on mismatch. Nothing else in the protocol changes.
