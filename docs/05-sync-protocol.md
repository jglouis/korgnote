# 05 — Local store and sync protocol

Addresses G4, G5 and defects D1, D2, D3, D8, D9, D10, D11.

## Server: a file store that tells the truth

Before any protocol work, the server must stop believing its startup snapshot (D1). This is
the one urgent fix in the whole plan, because the current design can overwrite edits you
made in Emacs.

```kotlin
class FileStore(private val root: Path) {
    fun list(): List<FileMeta>                    // walks the tree on demand
    fun read(path: String): ByteArray
    fun write(path: String, bytes: ByteArray, expectedEtag: String?): WriteResult
    fun delete(path: String, expectedEtag: String?): WriteResult
    fun watch(): Flow<FileChange>                 // java.nio WatchService
}

data class FileMeta(val path: String, val size: Long, val mtimeMillis: Long, val etag: String)
```

- `etag` is a **content hash** (SHA-256, hex-truncated to 16 bytes), not an mtime: Emacs and
  Syncthing rewrite mtimes freely, and a hash makes "did this change?" exact.
- Cache hashes keyed by `(path, size, mtime)` so a manifest request rehashes only what moved.
- `write` is atomic: write to a temporary file in the same directory, `fsync`, then rename.
  A crash or a dying connection must never truncate a note.
- Path validation on every call: reject absolute paths, `..` segments, and anything that
  resolves outside `root` after symlink resolution. Allow-list extensions
  (`.org`, `.txt`, `.md`) and cap file size. The current implicit protection — "the path
  must already be in the startup map" — disappears once creating files is allowed (D11), so
  this has to be explicit.

## Protocol

Versioned under `/api/v1`. All responses compressible (install Ktor's `Compression` plugin —
Org text gzips roughly 4–5×, D10).

### `GET /api/v1/manifest[?since=<generation>]`

```json
{
  "generation": 421,
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

Size check: ~120 bytes per entry, so 1,000 files ≈ 120 KB raw, ~20 KB gzipped, and that is
the *worst* case (a cold client). The steady state is a `since` query returning nothing.

### `GET /api/v1/files/{path...}`

Request `If-None-Match: <etag>` → `304 Not Modified` with no body, or `200` with the raw
bytes, `ETag`, and `Content-Type: text/plain; charset=utf-8`.

### `GET /api/v1/bundle[?since=<generation>]`

One request, many files — NDJSON, one JSON object per line, gzipped:

```
{"path":"inbox.org","etag":"9f2a…","text":"* TODO …"}
{"path":"projects/work.org","etag":"41bc…","text":"…"}
```

Needed by any client that starts empty and wants the whole corpus: the web app on every
page load, and a freshly installed native client on first sync. Without it, a cold start is
N conditional `GET`s, which is a poor trade even over HTTP/2. With `since`, it returns only
what changed — making it equally useful as a catch-up after a device has been offline for a
while.

Stream the response rather than building it in memory, and cap it: beyond a size threshold,
return what fits plus a `"truncated": true` marker line and let the client page with
`since`.

### `PUT /api/v1/files/{path...}`

Body is the raw file content. Headers:

| Header | Meaning |
|---|---|
| `If-Match: <etag>` | "I am editing the version with this hash." → `409 Conflict` if the server has moved on |
| `If-None-Match: *` | "Create only — fail if it exists." (new notes, capture) |

Responses: `200 {"etag": "…"}`, `409` with the current `ETag` so the client can fetch and
merge, `413` over size limit, `422` invalid path.

> `409` rather than `412` is the pragmatic choice: this is a *mergeable* conflict, not a
> failed precondition the client should give up on. Either is defensible; pick one and
> document it — the client behaviour is what matters.

### `DELETE /api/v1/files/{path...}`

`If-Match` required. `204` on success. Server moves the file to a trash directory
(`.korgnote-trash/<date>/…`) rather than unlinking — cheap insurance for a sync bug.

### `GET /api/v1/events` (SSE)

```
event: changed
data: {"generation":422,"path":"inbox.org","etag":"7b31…"}

event: deleted
data: {"generation":423,"path":"old.org"}
```

Fed by the file watcher, so changes made in Emacs or delivered by Syncthing reach the
clients within seconds. SSE over WebSocket because it is one-directional, survives proxies,
reconnects natively, and needs no extra dependency. Clients must treat it as a *hint* —
correctness always comes from the manifest diff.

### Auth

`Authorization: Bearer <token>`, token stored alongside host/port in settings and read by
the server from `KORGNOTE_TOKEN`. If the variable is unset, the server binds `127.0.0.1`
only and logs a warning — today it binds `0.0.0.0` with no auth at all (D8). TLS is a
reverse proxy's job (Caddy/nginx); document it in the README rather than implementing it.

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

On desktop and Android, `notes/` can point at a real user-visible folder, which lets
Syncthing keep working on those platforms during migration. On iOS it lives in the app
container (optionally exposed via the Files app).

`DocumentStore` owns this directory and is the only writer. It exposes documents, marks
them dirty on save, and emits change events that the ViewModels observe. Access goes
through `ReplicaStorage` (Okio or `kotlinx-io` for the filesystem implementation), which is
also what lets the store be tested without touching a disk.

### Web: the same store, backed by memory

The web build is always served by this same server, so it is online by definition. It uses
`InMemoryReplicaStorage` and the identical `DocumentStore`/`SyncEngine`:

| | Native | Web |
|---|---|---|
| Initial load | manifest diff, fetch stale files, cached across restarts | `GET /api/v1/bundle` on page load |
| Save | write locally (always succeeds), push debounced | autosave debounced (~1 s) straight to `PUT`; document stays dirty and retries if it fails |
| Live updates | SSE → targeted pull | SSE → targeted pull (same code; browsers reconnect natively) |
| Base copies | on disk, survive restarts | in memory, lost on reload |
| Conflicts | three-way merge | three-way merge while the base is live; after a reload, fall back to a conflict copy |
| Notifications, background sync | real | no-op |

The only behavioural difference the user can perceive is that a browser reload starts from
the server — which, with autosave, means losing at most a second of typing. Optionally keep
a `localStorage` draft per path to close even that gap.

See [02-assessment.md](02-assessment.md#the-web-target-keep-it-as-a-full-online-client)
for the cost analysis behind this choice.

## Sync algorithm

Per path, compare three versions: **L** local, **B** base (last synced), **R** remote
(manifest entry).

| L vs B | R vs B | Action |
|---|---|---|
| same | same | nothing |
| same | changed | **pull**: fetch R, write to `notes/` and `base/` |
| changed | same | **push**: `PUT` with `If-Match: B.etag`; on success update `base/` |
| changed | changed, `L == R` | converged independently: update `base/` only |
| changed | changed, `L != R` | **merge** (below) |
| deleted locally | same | `DELETE` with `If-Match` |
| same | deleted remotely | delete locally |
| changed | deleted remotely | keep local, re-push as a new file, log it — never discard an edit |

### Merging

`shared/sync/merge`: a line-based three-way merge (diff3) over `B`, `L`, `R`.

- Clean merge → write the result locally, `PUT` it with `If-Match: R.etag`, update base.
- Conflicting hunks → **do not** write conflict markers into your notes. Keep `L` as the
  live file, write `R` beside it as `name.sync-conflict-20260920-101500.org`, update base to
  `R`, and surface a banner in the UI. The naming deliberately mirrors Syncthing, which you
  already know how to deal with.

Org files merge well in practice: edits usually land under different headings, and a
line-level diff3 resolves those without help. ~200 lines of pure Kotlin, and the most
test-friendly component in the system.

### Triggers

| Trigger | Behaviour |
|---|---|
| App start / foreground | full sync cycle |
| SSE `changed` event | targeted pull of that path |
| Local save | mark dirty, debounce 2–5 s, push |
| Manual pull-to-refresh | full cycle |
| While foregrounded | periodic manifest poll (~5 min) as an SSE backstop |
| Android background | `WorkManager` periodic (15 min floor) |
| iOS background | `BGAppRefreshTask`, opportunistic — **assume it may not run** |

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

1. Add `/api/v1/*` alongside the existing endpoints; server keeps working for the current
   client.
2. Move the client to `DocumentStore` + `SyncEngine`, reading and writing locally, syncing
   in the background.
3. Delete `GET /api/org-content`, `GET /api/org-tree-paths`, `GET|POST /api/content/{…}` and
   `KtorOrgRepository`. The file tree is then built from the local replica by the existing
   `TreeUtils.buildFromPaths`, which moves client-side unchanged.

Step 3 is also what retires the last user of AST serialization, unblocking the model
redesign in [04-org-core.md](04-org-core.md).
