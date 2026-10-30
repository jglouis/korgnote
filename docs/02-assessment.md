# 02 — Assessment: are we going in the right direction?

Short answer: **the stack is right, the data architecture is not.**

Compose Multiplatform + a KMP `shared/` module + a Ktor JVM server is a sound answer to G7
and should be kept. The problem is that the system treats the *server* as the owner of the
documents and ships *parsed domain objects* over the wire. Every stated goal that is
currently hard — offline editing, low bandwidth, faithful round-tripping, coexistence with
Emacs and Syncthing — is hard for that one reason.

The corrective move is a single architectural inversion, detailed in
[03-target-architecture.md](03-target-architecture.md):

> The `.org` files are the source of truth, each client holds a **full local replica**, all
> parsing happens **on the device**, and the server becomes a **dumb file-sync endpoint**
> that moves bytes and metadata — never ASTs.

## Verdict per design choice

| Design choice | Verdict | Reasoning |
|---|---|---|
| Compose Multiplatform for all clients | **Keep** | One UI codebase across desktop/Android/iOS is exactly G7. Main risk is iOS text editing quality — see [Risks](#risks-worth-retiring-early). |
| `shared/` KMP module for the Org model | **Keep, and expand** | The best decision in the project. The parser already runs on every target; it should therefore run *on the client*, which is what makes the server dumb. |
| Ktor server on the JVM, reusing `shared/` | **Keep** | Same language, same model, one build. Cheap to operate. |
| Koin DI, `expect/actual` platform seams | **Keep** | Appropriate and unobtrusive at this size. |
| Test discipline in `shared/` (12 parser test files) | **Keep** | This is what makes the model redesign in [04](04-org-core.md) safe to attempt. |
| **Server parses Org and returns ASTs** | **Change** | Costs bandwidth (D2), forces online use, and caused the lossy round-trip bug (D4). The parser is multiplatform — there is no reason to run it server-side. |
| **Server caches the corpus at startup** | **Change (urgent)** | You edit these files in Emacs and sync them with Syncthing. A server that read the disk once at boot is wrong about the world within minutes (D1), and can overwrite your Emacs edits. |
| **No local document store on the client** | **Change** | Offline editing (G4) is not a feature you can add to this; it is the thing the client must be built around. |
| Lossy AST as the editable representation | **Change** | Already patched at the buffer level; fix properly with a source-mapped model (D4). |
| Two Org grammars (parser vs highlighter) | **Change** | Every new construct otherwise costs two implementations that drift (D5). |
| Calendar keyed on `SCHEDULED`-with-plain-date | **Change** | G6 needs `DEADLINE`, ranges, times and repeaters, which requires a real timestamp model (D7). |
| `wasmJs` target | **Keep, as a full online client** | Always served by the same server, so offline is a non-goal. Costs ~120 lines once the replica sits behind a storage interface. Since pairing, it works only on the server's own computer. See [The web target](#the-web-target-keep-it-as-a-full-online-client). |
| No auth on a `0.0.0.0` listener | **Change** | Acceptable for a LAN toy, not for something holding your notes and reachable for sync (D8). Done as device pairing rather than the shared token first planned: TLS with a pinned certificate and a token per device, and a plain port for the server's own computer — see [05](05-sync-protocol.md#auth). |

## Is the REST API "too cute"?

Your instinct is right that something is off, but the diagnosis is worth sharpening: the
API is not too clever, it is **modelling the wrong resource**. It exposes
`Map<path, OrgDocument>` — an internal domain object — where it should expose *files and
their versions*. The recent need to add `GET /api/content/{path}` to retrieve raw text was
that mismatch surfacing: the editor needed the bytes, and the API was only offering
opinions about the bytes.

The fix is not to abandon HTTP. HTTP with conditional requests is precisely a file-sync
protocol, and it is the most boring, most maintainable option available to you:

| Concern | HTTP mechanism |
|---|---|
| "Has this file changed?" | `ETag` + `If-None-Match` → `304 Not Modified`, no body |
| "Save only if nobody else did" | `If-Match` → `409 Conflict` = conflict detected (`412` would do too; see [05](05-sync-protocol.md#put-apiv1filespath)) |
| "What changed since I last synced?" | one manifest endpoint with a `since` token |
| "Tell me when something changes" | SSE (or WebSocket) event stream |
| Payload size | `Compression` plugin — Org text gzips ~4–5× |

That is standard, debuggable with `curl`, and implementable in Ktor on both ends with no
new dependency. Full design in [05-sync-protocol.md](05-sync-protocol.md).

### Bandwidth, roughly

Assume 300 files / 3 MB of Org text — assumptions, not measurements; measure before
optimising further.

| Scenario | Today | Target |
|---|---|---|
| Cold start | entire corpus parsed to JSON, uncompressed: **~5–9 MB** (AST JSON typically 1.5–3× the source text) | manifest only: **~10–40 KB** gzipped, then fetch what is stale |
| App resumed, nothing changed | full corpus again | manifest (or one SSE event): **~10 KB or less** |
| One note edited elsewhere | full corpus again | one file, gzipped: **~2–10 KB** |
| Saving a note | whole file, uncompressed | whole file, gzipped, with conflict detection |

The cheap wins (stop shipping ASTs, enable compression, conditional GETs) are worth two
orders of magnitude. **Delta/rsync-style patching is deliberately deferred** — it is the
expensive, fiddly part, and whole-file sync of gzipped text is already far inside your
budget. [05-sync-protocol.md](05-sync-protocol.md#deferred-delta-sync) records where it
would hook in if measurement ever justifies it.

### Alternatives considered and rejected

| Option | Why not |
|---|---|
| gRPC / protobuf | Toolchain and build complexity against G7; gains little over gzipped text for this payload shape. |
| Custom WebSocket protocol for everything | You would be reimplementing caching and conditional requests. Use SSE for *notifications only*, keep HTTP for transfer. |
| Git as the sync layer (JGit) | Semantically ideal for text notes — history, merges, existing tooling. But JGit is JVM-only, so iOS is out, and iOS is the reason this project exists. Revisit only if you drop iOS. |
| WebDAV / Nextcloud / CalDAV as backend | Delegates sync to a server you would still have to run, and you would write the same client-side replica and conflict logic anyway. *Later added on Android and iOS as an experimental third source, beside the server, not instead of it:* once that replica and conflict logic existed, a WebDAV folder needed only another `SyncApi` under the same engine, and lets someone who already runs Nextcloud skip the KorgNote server. It has no change stream (a 30 s poll), no delta, no bundle and no trash. CalDAV remains interesting later purely as an *export* of your agenda to other calendar apps. |
| Keep Syncthing, app reads a local folder | Works beautifully on desktop/Android and is arguably the "correct" Unix answer — but iOS has no Syncthing client and sandboxes the filesystem, which is exactly the gap you set out to close. *Later added on Android and iOS as an option beside the server, not as the design:* a folder the user picks, which Syncthing, Dropbox or iCloud Drive keeps in step. See [05](05-sync-protocol.md#three-places-for-the-notes). |

## Risks worth retiring early

1. **iOS text editing in Compose.** The core of this product is typing into a large
   monospace document with an IME, selection handles and a completion popup. Compose
   Multiplatform's iOS text field is the youngest part of the stack. Before building the
   completion engine and the new editor, spend a session on a spike: a 200 KB Org file in a
   `BasicTextField` on a real device — scrolling, selection, autocorrect behaviour, cursor
   placement. If it is unusable, that finding changes the roadmap (and possibly the iOS
   strategy) far more cheaply now than after T9–T11. Tracked as **T0** in
   [08-roadmap.md](08-roadmap.md).
2. **Background sync on iOS.** iOS will not let you sync on a schedule the way a daemon
   does. Design for sync-on-foreground plus locally-scheduled notifications rather than
   assuming background execution; see [07-calendar-agenda.md](07-calendar-agenda.md#notifications).
3. **Editor performance.** Full-document highlighting per keystroke (D6) will become the
   felt bottleneck before corpus size does.

## The web target: keep it, as a full online client

The web app is only ever served by the Ktor server it talks to. It is online by
definition, same-origin, and never has to survive a network partition. Offline is a
non-goal there, and that changes the analysis: the gap between "local-first native client"
and "always-online web client" is much smaller than it looks, **provided the local-first
design is built around the right seam**.

### The seam

The native replica is *"the corpus, plus a last-synced base copy, plus dirty flags."*
Nothing in that sentence requires a filesystem. Put the backing store behind one small
interface and the web client becomes the same client with a different store — not a
different application:

```kotlin
interface ReplicaStorage {
    suspend fun list(): List<String>
    suspend fun read(path: String): String?
    suspend fun write(path: String, text: String)
    suspend fun delete(path: String)
    suspend fun readBase(path: String): String?
    suspend fun writeBase(path: String, text: String)
    suspend fun meta(): MutableMap<String, FileMeta>
    suspend fun putMeta(meta: Map<String, FileMeta>)
}

class FileSystemReplicaStorage(root: Path) : ReplicaStorage   // desktop, Android, iOS
class InMemoryReplicaStorage : ReplicaStorage                 // web
```

`DocumentStore` and `SyncEngine` sit above it unchanged. The web client still pulls a
manifest, still holds the whole corpus, still tracks dirty documents, still detects
conflicts with `If-Match`, still three-way merges — it simply keeps all of that in memory
and starts empty on every page load. "Offline" then degrades naturally into "a push that
fails leaves the document dirty and retries", which is the same code path native uses.

| Layer | Native | Web | Shared |
|---|---|---|---|
| Compose UI, ViewModels | ✔ | ✔ | yes |
| Org core — parse, edit, spans, completion, search, agenda | ✔ | ✔ | yes |
| `DocumentStore`, `SyncEngine`, three-way merge | ✔ | ✔ | yes |
| Sync protocol client | ✔ | ✔ | yes |
| `ReplicaStorage` | filesystem | memory | interface shared |
| Corpus loading | incremental, cached across restarts | eager on page load | policy flag |
| Notification scheduler | real | no-op | `expect/actual`, already the pattern |
| Background sync triggers | none yet: sync at start, on resume and on change events ([05](05-sync-protocol.md#triggers)) | none needed | — |

### What it actually costs

Estimates, not measurements:

| Item | One-off | Ongoing |
|---|---|---|
| `ReplicaStorage` interface | ~20 lines | — |
| `InMemoryReplicaStorage` | ~40 lines | — |
| Eager "load everything on start" policy | ~20 lines | — |
| wasm `actual` no-ops (notifications, app-data dir) | ~20 lines | — |
| Optional: draft preservation across reload (`localStorage`) | ~30 lines | — |
| wasm build + source sets | already paid | CI build time |
| **Compose/wasm API lag** | — | **the real ongoing cost — see below** |
| Browser QA | — | a periodic manual pass |

Call it **~100–130 lines of web-specific code**, most of which is an interface that native
benefits from anyway (it makes `DocumentStore` testable without touching a disk). That is
not the burden the earlier draft implied, and it does not justify degrading the target to
view-only.

### Where the abstraction leaks — and why that is acceptable

1. **Saving can fail.** Native saves to disk and syncs later, so a save effectively always
   succeeds. On web, a save *is* the network call. The UI therefore needs an honest save
   state (saving / saved / conflict) that native rarely exercises. This is a genuine leak,
   and it is the *good* kind: it forces the interface to admit something that was always
   true (a sync can conflict), instead of hiding it behind a store that never says no.
2. **A page reload loses unsaved edits and the merge ancestor.** Mitigated by pushing each
   save to the server at once — which is also the behaviour that makes web feel like
   native — plus optionally a `localStorage` draft (not built). After a reload without a base copy,
   conflict resolution falls back to "keep both" instead of a three-way merge, which is
   acceptable for a case that autosave makes rare.
3. **Cold load pays for the corpus.** See the bulk-fetch note below.
4. **Feature parity needs a moment's thought per feature**, not a parallel implementation:
   "does this make sense with a RAM store and no background execution?" The answer is
   usually yes, occasionally no-op (notifications).

### The real risk is not the architecture

It is **text editing quality in Compose for wasm**. Compose renders to a canvas in the
browser, so the editor does not get native text input: IME behaviour, selection handles,
browser find-in-page, copy/paste edge cases and mobile browsers are all rougher than on
desktop JVM. Exactly the same class of risk as iOS ([Risks](#risks-worth-retiring-early)),
and exactly the same mitigation — a short spike, tracked as **T0b** in
[08-roadmap.md](08-roadmap.md).

You are better placed than this document to judge that: you already use the web build. If
editing in the browser feels good enough today, keeping it as a full client is a cheap,
well-bounded decision. If it does not, that is a *UI toolkit* verdict, not an argument
about sync architecture.

### A bonus: the web target improves the API

An always-online client that needs the whole corpus on every page load exposes a weakness
in the protocol as designed: 300 conditional `GET`s is a poor cold start. The fix is one
endpoint —

```
GET /api/v1/bundle[?since=<generation>]   → NDJSON: {path, etag, text} per line, gzipped
```

— which is *also* the better cold start for a fresh native client. Keeping web in scope
applies steady pressure on the sync API to stay simple and bulk-friendly. It was also meant
as a zero-install way to reach your notes from any machine; since pairing it is not. The TLS
port serves nothing outside `/api/`, and the plain port answers only a `Host` naming the
server's own computer, so a reverse proxy passing on its own name gets `403`; and a browser
cannot pair.
It is now a client for the computer the server runs on. Even so, it is worth more than the
~120 lines it costs.

## What this means for sequencing

The one urgent fix is **D1** (server must re-read the disk): it is small, isolated, and the
current behaviour can *lose your Emacs edits*. Everything else follows the phases in
[08-roadmap.md](08-roadmap.md): core model → local-first sync → editor → agenda.
