# 05 — Local store and sync protocol

Addresses G4, G5 and defects D1, D2, D3, D8, D9, D10, D11.

## Server: a file store that tells the truth

Before any protocol work, the server must stop believing its startup snapshot (D1). This is
the one urgent fix in the whole plan, because the current design can overwrite edits you
made in Emacs.

```kotlin
class FileStore(val root: Path) {
    fun list(): List<FileMeta>                    // walks the tree on demand
    fun read(path: String): String?
    fun meta(path: String): FileMeta?
    fun put(path: String, content: String, ifMatch: String?, mustNotExist: Boolean): WriteResult
    fun delete(path: String, ifMatch: String?): WriteResult
}

data class FileMeta(val path: String, val size: Long, val mtimeMillis: Long, val etag: String)
```

Watching is a class of its own, `server/…/NotesWatcher.kt` (a `java.nio` `WatchService`), which
turns each change on disk into a journal entry.

- `etag` is a **content hash** (SHA-256, hex-truncated to 16 bytes), not an mtime: Emacs and
  Syncthing rewrite mtimes freely, and a hash makes "did this change?" exact.
- Cache hashes keyed by `(path, size, mtime)` so a manifest request rehashes only what moved.
- `put` is atomic: write to a temporary file in the same directory, then rename. A crash or a
  dying connection never truncates a note. There is no `fsync` before the rename yet, so a
  power cut can still lose a save the filesystem had not flushed — a known gap.
- Path validation on every call: reject absolute paths, `..` segments, and anything that
  resolves outside `root` after symlink resolution — for a new file, the nearest folder that
  exists, so a note cannot be created through a folder linked in from elsewhere. Only `.org` is
  allowed, and files are capped at 32 MB. The current implicit protection — "the path
  must already be in the startup map" — disappears once creating files is allowed (D11), so
  this has to be explicit.

## Protocol

Versioned under `/api/v1`. All responses compressible (install Ktor's `Compression` plugin —
Org text gzips roughly 4–5×, D10).

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
`complete: true`. The client must handle both — which keeps the journal a pure optimisation.
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
file. The app never sends `If-None-Match` here: it only fetches what the manifest says moved.

ETags go out unquoted (`ETag: 9f2a…`); the server strips quotes from the ones it is sent.

### `GET /api/v1/bundle[?since=<generation>]`

One request, many files — one JSON object per line, served as `application/json`, gzipped:

```
{"path":"inbox.org","etag":"9f2a…","text":"* TODO …"}
{"path":"projects/work.org","etag":"41bc…","text":"…"}
```

Needed by any client that starts empty and wants the whole corpus: the web app on every
page load, and a freshly installed native client on first sync. Without it, a cold start is
N conditional `GET`s, which is a poor trade even over HTTP/2. With `since`, it returns only
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
| neither | Unconditional: create or replace. The server accepts it; the app never sends it. |

Responses: `200 {"etag": "…"}`, `409` with the current `ETag` so the client can fetch and
merge, `404` for `If-Match` on a file that is not there, `403` when the server is serving its
bundled samples read-only, `413` over size limit, `422` invalid path.

> `409` rather than `412` is the pragmatic choice: this is a *mergeable* conflict, not a
> failed precondition the client should give up on. Either is defensible; pick one and
> document it — the client behaviour is what matters.

### `DELETE /api/v1/files/{path...}`

`If-Match` required — `422` without it. `204` on success, `409` with the current `ETag` when
the file moved, `404` when it is already gone. Server moves the file to a trash directory
(`.korgnote-trash/<date>/…`) rather than unlinking — cheap insurance for a sync bug.

### `GET /api/v1/events` (SSE)

```
event: changed
data: {"generation":1758300000422,"path":"inbox.org","etag":"7b31…","deleted":false}

event: deleted
data: {"generation":1758300000423,"path":"old.org","etag":null,"deleted":true}
```

Fed by the file watcher, so changes made in Emacs or delivered by Syncthing reach the
clients within seconds. SSE over WebSocket because it is one-directional, survives proxies,
reconnects natively, and needs no extra dependency. Clients must treat it as a *hint* —
correctness always comes from the manifest diff.

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
| 8443, TLS (`KORGNOTE_HTTPS_PORT`) | paired devices | the sync API only, with `Authorization: Bearer <device token>`; `POST /api/pair` excepted, which is how a device gets one. Anything outside `/api/`, or under `/api/local/`, is `404`; an unknown or revoked token `401`. |
| 8080, plain (`KORGNOTE_PORT`) | this computer | no token. Bound to `127.0.0.1` (`KORGNOTE_HTTP_HOST`). The `Host` must name this computer (`localhost` or a loopback address), or `403` — which stops DNS rebinding — and a change that carries an `Origin` must come from the server's own pages. Serves the web client, the desktop app, the Devices page (`/devices`) and `POST /api/local/pairing`, which `scripts/pair.sh` calls. |

TLS uses a self-signed certificate the server makes on first start and keeps, with its id and
the paired devices, in `KORGNOTE_STATE_DIR` (`~/.korgnote-server`). The Devices page shows a QR
code holding the server's id, its addresses (`KORGNOTE_ADVERTISED_ADDRESSES`,
`KORGNOTE_ADVERTISED_PORT` for a container), the certificate's SHA-256 and a one-time code. The
device trades the code at `POST /api/pair` for a token of its own, over a client that trusts
that certificate and nothing else (`shared/…/pairing/PinnedTrustManager.kt`); the server keeps
only a hash of the token.

What this trusts, stated plainly:

- **Anything that reaches port 8080 with `Host: localhost` has full read and write**, and can
  mint pairing codes. On a desktop that is every local process. In Docker the plain port listens
  on `0.0.0.0` inside the container (`KORGNOTE_HTTP_HOST=0.0.0.0`) and is published on the host's
  loopback only, so other containers on the same Docker network reach it too.
- The device token is sealed on Android (a Keystore key) and iOS (the Keychain). The desktop app
  keeps it in plain Java `Preferences` (`composeApp/…/PersistentSettingsRepository.jvm.kt`).

## Client: the local replica

```
<app-data>/
  notes/                          the replica — real .org files, the app's working copy
    inbox.org
    projects/work.org
  .korgnote/
    base/                         last-synced content, same tree shape (merge ancestor)
      inbox.org
    meta.json                     per path: etag, size, mtime, dirty, lastSyncedGeneration
```

Keeping `base/` as real files doubles disk usage of a few megabytes of text and buys
offline three-way merges — the alternative (fetching the ancestor at conflict time) fails
exactly when you are offline, which is when conflicts get created.

The replica lives in the app's own storage on every platform; it is never a user-visible
folder. Keeping notes in such a folder is a separate source, below.

`DocumentStore` owns this directory and is the only writer. It exposes documents, marks
them dirty on save, and emits change events that the ViewModels observe. Access goes
through `ReplicaStorage` (Okio or `kotlinx-io` for the filesystem implementation), which is
also what lets the store be tested without touching a disk.

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
| Initial load | manifest diff, fetch stale files, cached across restarts | manifest, then `GET /api/v1/bundle` on page load |
| Save | write locally (always succeeds), push at once | the same, into memory; document stays dirty and retries if the push fails |
| Live updates | SSE → targeted pull | SSE → targeted pull (same code, same reconnect loop) |
| Base copies | on disk, survive restarts | in memory, lost on reload |
| Conflicts | three-way merge | three-way merge while the base is live; after a reload, fall back to a conflict copy |
| Notifications, background sync | real | no-op |

The only behavioural difference the user can perceive is that a browser reload starts from
the server — which, since every save is pushed at once, means losing only an edit still open
and not yet saved. Optionally keep a `localStorage` draft per path to close even that gap; it is
not built.

See [02-assessment.md](02-assessment.md#the-web-target-keep-it-as-a-full-online-client)
for the cost analysis behind this choice.

## Sync algorithm

Per path, compare three versions: **L** local, **B** base (last synced), **R** remote
(manifest entry). One cycle runs at a time (`shared/…/replica/SyncEngine.kt`): a save, the
change stream and a retry each start one, and two running together sent the same pending
deletion three times. One that waits finds the work done.

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
| changed | deleted remotely | keep local, re-push as a new file, log it — never discard an edit |
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
- Conflicting hunks → **do not** write conflict markers into your notes. **The server is the
  source of truth**: `R` keeps the path, in `notes/` and `base/`, and `L` is written beside it
  as `name.sync-conflict-20260920-101500.org`, a new note the next cycle pushes. A file in the
  notes directory is therefore never replaced by a version nobody merged — Emacs never sees its
  file rewritten under it — and both conflict cases, edited and created, follow one rule. The
  naming deliberately mirrors Syncthing, which you already know how to deal with; a second
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
carries no heading information. Headings come in when comparing.

- **Top bar**: a warning with the count of notes in conflict; a tap lists them.
- **File tree**: the note is flagged, and each copy is drawn under it, newest first.
- **Banner** on the note or a copy: which version is on screen, and *Keep this one* (delete the
  copy, which the server moves to its trash), *Use the other* (the copy's text replaces the
  note, pushed as an edit of the server's version, then the copy is deleted) and *Compare*.
- **Compare**: only the differing lines, with context, each hunk under the heading it falls in.

When a sync ends in a conflict the message line says so, since the open note's text changes
under the reader; and any sync that moved the replica redraws it, a save's included — left on
screen, the old text would be written back by the next save. An element open in the editor when
a sync lands is not replaced under the caret: the newer note is set aside, and saving makes the
edit on it, merged line by line; only a change to the same lines is refused
(`composeApp/…/viewmodel/ElementEditor.kt`).

Org files merge well in practice: edits usually land under different headings, and a
line-level diff3 resolves those without help. ~200 lines of pure Kotlin, and the most
test-friendly component in the system.

### Triggers

What is built (`composeApp/…/viewmodel/ServerSync.kt`):

| Trigger | Behaviour |
|---|---|
| App start | full sync cycle |
| Coming back to the app | full cycle, and the change stream is woken out of its backoff: changes made elsewhere are most likely waiting then |
| SSE `changed` / `deleted` event | targeted pull or removal of that path |
| Change stream lost or reconnected | full cycle, since the stream missed whatever changed meanwhile; reconnects back off from 1 s to 30 s |
| Change stream silent 90 s after a heartbeat | taken as dead: reconnect and full cycle |
| Local save | mark dirty, and run a cycle at once, which pushes it — no debounce |
| Manual retry | full cycle |

Not built: a periodic manifest poll while in the foreground (~5 min, as an SSE backstop),
Android periodic `WorkManager` sync, and iOS `BGAppRefreshTask`. `WorkManager` is used only for
a one-shot sync when a home-screen widget's sync button is tapped
(`androidApp/…/widget/WidgetSync.kt`).

iOS will not give you daemon-like background sync. Design for it: a foreground sync is
fast, reminders are scheduled locally in advance
([07](07-calendar-agenda.md#notifications)), and nothing user-visible depends on a
background wake-up.

## Deferred: delta sync

Whole-file transfer of gzipped text is already inside budget, so **do not build deltas
now**. If measurement ever justifies it, the hook is: `PUT` accepts
`Content-Type: application/vnd.korgnote.patch` with `If-Match` naming the base version, body
being a unified diff produced by the same diff engine the merge uses. Server applies it,
verifies the resulting hash matches a client-supplied `X-Result-Etag`, and falls back to
requesting a full `PUT` on mismatch. Nothing else in the protocol changes.

## Migration from the current API

All three steps are done; the old endpoints went in T10.

1. Add `/api/v1/*` alongside the existing endpoints; server keeps working for the current
   client.
2. Move the client to `DocumentStore` + `SyncEngine`, reading and writing locally, syncing
   in the background.
3. Delete `GET /api/org-content`, `GET /api/org-tree-paths`, `GET|POST /api/content/{…}` and
   `KtorOrgRepository`. The file tree is then built from the local replica by the existing
   `TreeUtils.buildFromPaths`, which moves client-side unchanged.

Step 3 is also what retires the last user of AST serialization, unblocking the model
redesign in [04-org-core.md](04-org-core.md).
