# 03 — Architecture

## Principles

1. **The `.org` files are the source of truth.** Not a database, not an AST. Anything the
   app cannot express survives a round trip byte-for-byte.
2. **Every client holds the full corpus.** The corpus is small (megabytes). Holding all of
   it makes offline editing, instant search and instant navigation trivial, and turns sync
   into an occasional background concern rather than a request/response dependency.
   *Where* it is held is a platform detail behind `ReplicaStorage`: files on desktop,
   Android and iOS; memory on web, which is always online and starts fresh each page load
   ([below](#the-web-target)).
3. **Parsing happens on the device.** `shared/` compiles for every target.
4. **The server moves bytes and metadata, never ASTs.** It is a sync endpoint with a file
   watcher, not a document service.
5. **The server is one replica among several.** Its files may change underneath it by Emacs,
   Syncthing, or a text editor over SSH.
6. **Mutations are text edits.** Structural operations compute a text range and replace it;
   they never regenerate a document from its parse tree.

## Shape

```
┌────────────── client (desktop / Android / iOS / web) ───────────────────────────┐
│                                                                                 │
│  UI (Compose)                                                                   │
│    reader · element editor · file tree · calendar · search · completion strip   │
│        │                                                                        │
│  NotesViewModel: what crosses them, and one plain holder per concern            │
│        │                                                                        │
│  ┌─────────────────┐   ┌──────────────────┐   ┌────────────────────────────┐    │
│  │ DocumentStore   │   │ AgendaIndex      │   │ searchOrg                  │    │
│  │ corpus          │   │ occurrences by   │   │ a linear scan of the       │    │
│  │ + base copies   │   │ date range       │   │ corpus; no index           │    │
│  │ + dirty flags   │   └──────────────────┘   └────────────────────────────┘    │
│  └────────┬────────┘            ▲                          ▲                    │
│           │                     └──────── shared/ org core ┘                    │
│    ReplicaStorage                 parse · agenda · completion · merge           │
│      files (native) │ memory (web)                                              │
│           │                                                                     │
│      SyncEngine                                                                 │
│        pull → merge → push, triggered by app start / save / SSE / foreground    │
└───────────┬─────────────────────────────────────────────────────────────────────┘
            │  HTTP: manifest · conditional GET · PUT If-Match · SSE events
┌───────────▼─────────────────────────────────────────────────────────────────────┐
│  server/ (Ktor, JVM)                                                            │
│    file store over NOTES_DIR  ·  metadata (hash, size, mtime)  ·  file watcher  │
│    TLS :8443 for paired devices — pinned certificate, a token per device        │
│    plain :8080 for this computer only — web build, desktop app, Devices page    │
│    compression                                                                  │
└───────────┬─────────────────────────────────────────────────────────────────────┘
            ▼
      .org files on disk  ←→  Emacs, Syncthing, anything else
```

The server is optional per device. On Android and iOS the notes can instead be a folder of the
user's, which another app such as Syncthing keeps in step, or a WebDAV folder (experimental)
synced by the same `SyncEngine` into a replica of its own; desktop and web use the server only.
See [05](05-sync-protocol.md#three-places-for-the-notes).

## Module boundaries

### `shared/` — the core of the product

Everything below is pure, multiplatform, and unit-testable without a UI or a server, under
`com.korgnote.common`:

| Package | Responsibility | Doc |
|---|---|---|
| `org` | Source-mapped, lossless document model, its one grammar (`OrgSyntax`, `OrgSpans`) and the element splitter | [04](04-org-core.md), [09](09-element-editing.md) |
| `org.edit` | Range-based structural edits (ticking a checkbox, setting a keyword or a property, clocking) | [04](04-org-core.md), [11](11-clocking.md) |
| `org.agenda` | Timestamps, repeaters, occurrence expansion, clocked time | [07](07-calendar-agenda.md) |
| `org.capture` | What was shared, the entry made of it, and filing it into a note | [10](10-capture.md) |
| `org.complete` | `(text, cursor) → candidates` | [06](06-editor.md) |
| `org.link` | What a link points at, backlinks, and every note and heading a link can go to | [09](09-element-editing.md#links-between-notes) |
| `org.search` | Query model and matching over the replica | [06](06-editor.md) |
| `org.table` | `#+TBLFM:` formulas, computed with Calc's arithmetic and printing | [09](09-element-editing.md#tables) |
| `org.template` | What a new note starts as | [08](08-roadmap.md#t20--creating-renaming-and-deleting-notes) |
| `replica` | `DocumentStore`, `SyncEngine`, `ReplicaStorage`, and the client's view of the server (`SyncApi`, `RemoteManifest`) | [05](05-sync-protocol.md) |
| `sync` | Three-way line merge, conflict-copy names and chunks, and the manifest's wire classes | [05](05-sync-protocol.md) |
| `pairing` | The pairing link and its request/response, shared by server and client; the pinned trust manager on the JVM | [05](05-sync-protocol.md#auth) |
| `fs` | The folder tree built from note paths, and what a note's path may be | |

Most of the sync wire format is not shared. The manifest's classes are (`shared/…/sync/Manifest.kt`),
and a test pins their field names; for the rest the server (`server/…/SyncApiV1.kt`) and the client
(`composeApp/…/data/KtorSyncApi.kt`) each declare their own small serializable classes. The JSON is the
contract.

### `composeApp/` — UI and device concerns

Compose UI, the view model and its holders, DI, settings, the sync client (`KtorSyncApi`,
`WebDavSyncApi`), and platform `expect/actual` for the app data directory and notification scheduling.
`DocumentStore`, `ReplicaStorage` and `SyncEngine` are in `shared/`.

Platform variation was meant to stay in three seams: `ReplicaStorage`, the app-data
directory, and `NotificationScheduler`. Features have added more, each still a small
interface or `expect` declaration: the `NoteFolder` a folder source reads through, the pairing
scanner, sealed secrets (Keystore on Android, Keychain on iOS), the HTTP client that pins the
paired certificate, the home-screen widgets, and flags such as `canPair` and
`usesLocalServer`. Everything else, including the sync engine and conflict merging, is one
implementation for all four targets.

There is one `NotesViewModel`, owning plain state holders, which the UI reads directly:
`NoteLibrary`, `OpenNote`, `Folds`, `NotePins`, `ReadingPlaces`, `NoteClocks`, `ElementEditor`,
`NoteChanges`, `NoteFiles`, `ClockActions`, `LinkNavigator`, `FileLinks`, `CompletionController`,
`TableEditing`, `StateNotes`, `LinkForm`, `NoteSearch`, `SavedSearches`, `NoteAgenda` and, behind the
`NotesSync` interface, `ServerSync` or `FolderSync`. Separate ViewModels would each have
needed the parsed notes and the open note, and every one of them follows an open, so they would
have spent their time notifying each other; the view model keeps only what crosses them: opening a
note, and rebuilding what is derived from the notes when they change.

### `server/` — thin

Ktor, static hosting of the web build, and a file store over `NOTES_DIR` that always reads current disk
state, computes metadata, and has a `WatchService`-based watcher feeding an SSE endpoint. Compression,
and device pairing: TLS with its own certificate and a token per device for the network, a plain port
for its own computer only ([05](05-sync-protocol.md#auth)). The server does not parse Org at all.

## Ownership of state

| State | Owner | Notes |
|---|---|---|
| Document text | client replica, mirrored to server disk | byte-exact both ways |
| Last-synced base copy | client only | needed for three-way merge; never uploaded |
| Dirty/queued-for-push flags | client only | survives restart on native, so an offline edit is never lost; on web it lives for the page session, with each save pushed at once to keep the window short |
| ETag / content hash | server computes, device caches | the sync currency |
| Parse trees, agenda index | device, in memory | derived, disposable, rebuilt from text. A burst of change-stream events is shown as one, rereading only the paths they name ([05](05-sync-protocol.md#triggers)) |
| Settings, and the paired server (id, addresses, certificate fingerprint, device token) | device | the token is sealed on Android (Keystore), iOS (Keychain) and desktop (a key in the system's keyring); the web client cannot pair |
| Paired devices, certificate, server id | server, in `KORGNOTE_STATE_DIR` | only a hash of each device's token is kept |

## Consequences worth stating

- **The native app works with the server switched off.** Read, edit, search, calendar and
  notifications all run against the local replica; sync is what happens when the network
  is there.
- **The web app is the same app with a memory-backed store.** It loads the corpus on page
  start, saves through the same sync engine, and gets search, completion and agenda
  for free, because those run on the corpus rather than on the server. Since pairing it
  works only on the server's own computer.
- **Syncthing can keep running.** The server reads the disk on demand, so a file arriving
  via Syncthing is picked up like any other external change. The two mechanisms coexist
  rather than compete. A phone can even skip the server and keep its notes in the folder Syncthing
  syncs.
- **Nothing is lost in translation.** With a lossless model, an Org construct the app does
  not understand yet is still preserved through an edit-and-sync cycle.
- **Features are not server round trips.** Completion, search and agenda queries run
  locally, which is what makes them feel instant (G2) and work on a train (G4).

## The web target

The web app is only ever served by the Ktor server it talks to. It is online by definition,
same-origin, and never has to survive a network partition. Offline is a non-goal there, and that
is what keeps the gap small between it and the local-first native client: it is the same client with
`InMemoryReplicaStorage` under the same `DocumentStore` and `SyncEngine`, and about 250 lines of its
own, which was reason enough to keep it a full client rather than a view-only one. The native replica is
"the corpus, plus a last-synced base copy, plus dirty flags", and nothing in that needs a filesystem.

It was also meant as a zero-install way to reach the notes from any machine; since pairing it is not.
The TLS port serves nothing but pairing and the sync API, and the plain port answers only a `Host`
naming the server's own computer, so a reverse proxy passing on its own name gets `403`; and a browser
cannot pair. It is a client for the computer the server runs on. It also keeps steady pressure on the
sync API to stay simple and bulk-friendly: a client that needs the whole corpus on every page load is
why the bundle endpoint exists, which is also the better first sync for a native client.

Where the abstraction leaks, and why that is acceptable:

1. **Saving can fail.** Native saves to disk and syncs later, so a save effectively always succeeds.
   On web, a save reaches the server only by the network. This is the good kind of leak: it admits
   something that was always true (a sync can conflict), instead of hiding it behind a store that
   never says no.
2. **A page reload loses unsaved edits and the merge ancestor.** Mitigated by pushing each save to the
   server at once, which is also what makes web feel like native. A `localStorage` draft would close
   the rest; it is not built. After a reload without a base copy, conflict resolution falls back to a
   conflict copy instead of a three-way merge.
3. **Cold load pays for the corpus.** The bundle is what keeps that to one request.
4. **Feature parity needs a moment's thought per feature**, not a parallel implementation: "does this
   make sense with a RAM store and no background execution?" The answer is usually yes, occasionally
   no-op (notifications).

The real risk was never the architecture but text editing in Compose for wasm: Compose renders to a
canvas in the browser, so IME behaviour, selection handles, browser find-in-page and mobile browsers
are all rougher than on the desktop JVM. That would be a verdict on the UI toolkit, not on the sync
design.

## Decisions and rejected alternatives

**The API models files and versions, not documents.** The first server exposed
`Map<path, OrgDocument>`, an internal domain object, and needed a separate endpoint for raw text: the
editor needed the bytes, and the API was only offering opinions about the bytes. HTTP with conditional
requests is precisely a file-sync protocol, and the most boring, most maintainable option available:
`ETag` with `If-None-Match` for "has this changed", `If-Match` and a `409` for "save only if nobody else
did", one manifest with a `since` token for "what changed", SSE for "tell me when", and gzip for size.
It is debuggable with `curl` and needs no new dependency on either end. Delta patching is deferred
([05](05-sync-protocol.md#deferred-delta-sync)).

| Option | Why not |
|---|---|
| gRPC / protobuf | Toolchain and build complexity against G7; gains little over gzipped text for this payload shape. |
| Custom WebSocket protocol for everything | It would reimplement caching and conditional requests. SSE carries *notifications only*, and HTTP the transfer. |
| Git as the sync layer (JGit) | Semantically ideal for text notes: history, merges, existing tooling. But JGit is JVM-only, so iOS is out, and iOS is the reason this project exists. Worth revisiting only if iOS goes. |
| WebDAV / Nextcloud / CalDAV as the backend | Delegates sync to a server that still has to be run, and the same client-side replica and conflict logic get written anyway. *Added later on Android and iOS as an experimental third source, beside the server, not instead of it:* once that replica and conflict logic existed, a WebDAV folder needed only another `SyncApi` under the same engine, and lets someone who already runs Nextcloud skip the KorgNote server. It has no change stream (a 30 s poll), no delta, no bundle and no trash. CalDAV remains interesting later purely as an *export* of the agenda to other calendar apps. |
| Keep Syncthing, app reads a local folder | Works beautifully on desktop and Android and is arguably the "correct" Unix answer, but iOS has no Syncthing client and sandboxes the filesystem, which is exactly the gap this project set out to close. *Added later on Android and iOS as an option beside the server, not as the design:* a folder the user picks, which Syncthing, Dropbox or iCloud Drive keeps in step. See [05](05-sync-protocol.md#three-places-for-the-notes). |
