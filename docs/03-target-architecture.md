# 03 — Target architecture

## Principles

1. **The `.org` files are the source of truth.** Not a database, not an AST. Anything the
   app cannot express must still survive a round trip byte-for-byte.
2. **Every client holds the full corpus.** The corpus is small (megabytes). Holding all of
   it makes offline editing, instant search and instant navigation trivial, and turns sync
   into an occasional background concern rather than a request/response dependency.
   *Where* it is held is a platform detail behind `ReplicaStorage`: files on desktop,
   Android and iOS; memory on web, which is always online and starts fresh each page load
   ([02](02-assessment.md#the-web-target-keep-it-as-a-full-online-client)).
3. **Parsing happens on the device.** `shared/` already compiles for every target.
4. **The server moves bytes and metadata, never ASTs.** It is a sync endpoint with a file
   watcher, not a document service.
5. **The server is one replica among several.** It must tolerate its files being changed
   underneath it by Emacs, Syncthing, or a text editor over SSH.
6. **Mutations are text edits.** Structural operations compute a text range and replace it;
   they never regenerate a document from its parse tree.

## Target shape

```
┌────────────── client (desktop / Android / iOS / web) ───────────────────────────┐
│                                                                                 │
│  UI (Compose)                                                                   │
│    editor · reader · file tree · calendar · search · completion popup           │
│        │                                                                        │
│  ViewModels (per screen, not one god object)                                    │
│        │                                                                        │
│  ┌─────────────────┐   ┌──────────────────┐   ┌────────────────────────────┐    │
│  │ DocumentStore   │   │ AgendaIndex      │   │ SearchIndex                │    │
│  │ corpus          │   │ occurrences by   │   │ in-memory, rebuilt from    │    │
│  │ + base copies   │   │ date range       │   │ the corpus                 │    │
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

### `shared/` — grows into the core of the product

Everything below is pure, multiplatform, and unit-testable without a UI or a server:

| Package | Responsibility | Doc |
|---|---|---|
| `org.model` | Source-mapped, lossless document model | [04](04-org-core.md) |
| `org.parse` | One grammar — the only place Org syntax is defined | [04](04-org-core.md) |
| `org.edit` | Range-based structural edits (toggle checkbox, set TODO, reschedule) | [04](04-org-core.md) |
| `org.agenda` | Timestamps, repeaters, occurrence expansion | [07](07-calendar-agenda.md) |
| `org.complete` | `(text, cursor) → candidates` | [06](06-editor.md) |
| `org.search` | Query model and matching over the replica | [06](06-editor.md) |
| `replica` | `DocumentStore`, `SyncEngine`, `ReplicaStorage`, and the client's view of the server (`SyncApi`, `RemoteManifest`) | [05](05-sync-protocol.md) |
| `sync` | Three-way line merge and conflict-copy names | [05](05-sync-protocol.md) |
| `pairing` | The pairing link and its request/response, shared by server and client; the pinned trust manager on the JVM | [05](05-sync-protocol.md#auth) |

The sync wire format is not shared: the server (`server/…/SyncApiV1.kt`) and the client
(`composeApp/…/data/KtorSyncApi.kt`) each declare their own small serializable classes, and the
client maps its own onto `RemoteManifest`. The JSON is the contract.

New dependency required in `shared/`: `kotlinx-datetime` (currently only in `composeApp`),
plus `kotlinx-serialization-json` for the replica's `meta.json` and the pairing messages.

### `composeApp/` — UI and device concerns

Keeps: Compose UI, ViewModels, DI, settings. Gains: `DocumentStore` + `ReplicaStorage`
(files on native, memory on web), `SyncEngine`, platform `expect/actual` for the app data
directory and notification scheduling. Loses: all Org knowledge that is not presentation
(it moves to `shared/`).

Platform variation was meant to stay in three seams — `ReplicaStorage`, the app-data
directory, and `NotificationScheduler`. Features have added more, each still a small
interface or `expect` declaration: the `NoteFolder` a folder source reads through, the pairing
scanner, sealed secrets (Keystore on Android, Keychain on iOS), the HTTP client that pins the
paired certificate, the home-screen widgets, and flags such as `canPair` and
`usesLocalServer`. Everything else, including the sync engine and conflict merging, is one
implementation for all four targets.

Split `FileTreeViewModel` (currently holds everything) into `LibraryViewModel` (tree,
search, sync status), `DocumentViewModel` (one open document, editing, completion) and
`CalendarViewModel` (agenda window). They observe the same `DocumentStore`.

*As built:* one `NotesViewModel` owning plain state holders instead — `NoteLibrary`,
`OpenNote`, `Folds`, `ElementEditor`, `CompletionController`, `NoteSearch`, `LinkNavigator`,
`NoteAgenda`, `ServerSync` — which the UI reads directly. Separate ViewModels would each have
needed the parsed notes and the open note, and every one of them follows an open, so they would
have spent their time notifying each other; the view model keeps only what crosses them.

### `server/` — thin

Keeps: Ktor, static hosting of the web build. Gains: file-store abstraction over `NOTES_DIR`
that always reads current disk state, metadata computation, a `WatchService`-based watcher
feeding an SSE endpoint, compression, and device pairing: TLS with its own certificate and a
token per device for the network, a plain port for its own computer only
([05](05-sync-protocol.md#auth)). Loses: `toOrgDocument()` on the request path — the server
stops parsing Org entirely.

> The server keeps one small Org-aware capability in reserve: nothing today, but if
> corpus size ever outgrows client-side search, a server-side index is the natural place
> for it. Do not build it pre-emptively.

## Ownership of state

| State | Owner | Notes |
|---|---|---|
| Document text | client replica, mirrored to server disk | byte-exact both ways |
| Last-synced base copy | client only | needed for three-way merge; never uploaded |
| Dirty/queued-for-push flags | client only | survives restart on native, so an offline edit is never lost; on web it lives for the page session, with autosave keeping the window short |
| ETag / content hash | server computes, device caches | the sync currency |
| Parse trees, agenda index, search index | device, in memory | derived, disposable, rebuilt from text |
| Settings, and the paired server (id, addresses, certificate fingerprint, device token) | device | the token is sealed on Android (Keystore) and iOS (Keychain), but kept in plain Java `Preferences` on desktop; the web client cannot pair |
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
  rather than compete, which also gives you a safe migration path. A phone can even skip the
  server and keep its notes in the folder Syncthing syncs.
- **Nothing is lost in translation.** With a lossless model, an Org construct the app does
  not understand yet is still preserved through an edit-and-sync cycle.
- **Features stop being server round trips.** Completion, search and agenda queries run
  locally, which is what makes them feel instant (G2) and work on a train (G4).
