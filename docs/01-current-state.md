# 01 — Current state (as-is)

Snapshot of the system as of commit `2cc66f7`. ~4,700 lines of Kotlin across four Gradle
modules. This document is descriptive: judgements live in [02-assessment.md](02-assessment.md).

## Module map

```
shared/       KMP library: Org parser + model, filesystem tree model
              targets: android, iosArm64, iosSimulatorArm64, jvm, wasmJs
              deps: kotlinx-serialization-core only

server/       Ktor (Netty) JVM server, depends on shared/
              serves parsed documents, raw file content, and the wasm web app

composeApp/   Compose Multiplatform UI, depends on shared/
              targets: android, iosArm64, iosSimulatorArm64, jvm (desktop), wasmJs
              deps: Compose MP 1.12, Koin 4.2, Ktor client 3.6, kotlinx-datetime, lifecycle-viewmodel

androidApp/   Android application shell (Activity + Application, starts Koin)
iosApp/       Xcode project shell hosting MainViewController
```

## Data flow today

```
   .org files on server disk
            │  read once, at server startup            server/…/Application.kt:81,112
            ▼
   in-memory Map<path, String>
            │
            ├── GET /api/org-content     → parses EVERY file, returns Map<path, OrgDocument> as JSON   :169
            ├── GET /api/org-tree-paths  → folder tree built from the same key set                     :176
            ├── GET /api/content/{path}  → raw text of one file                                        :181
            └── POST /api/content/{path} → overwrite file on disk, update in-memory map                :189
            ▼
   KtorOrgRepository (client)
            ▼
   FileTreeViewModel — holds every parsed document in memory
            │  orgContents: Map<String, OrgDocument>                    composeApp/…/FileTreeViewModel.kt:75
            │  selectedFileContent: String  (the edit buffer)                                        :80
            │  scheduledByDate: Map<LocalDate, List<Pair<String, OrgNode>>>                          :108,230
            ▼
   App.kt  ── read mode  → OrgText(selectedOrgNode)        rendered from the parsed AST
           └─ edit mode  → OutlinedTextField(selectedFileContent) + OrgSyntaxHighlightTransformation
```

## Component detail

### `shared/` — Org model and parser

`shared/src/commonMain/kotlin/com/korgnote/common/org/OrgMode.kt`

- Node types (all `@Serializable`, lines 7–66): `OrgDocument`, `OrgSection`, `OrgList`,
  `OrgKeyword`, `OrgPlanning`, `OrgRaw`, `OrgCheckboxItem`, `OrgTable`, `OrgTableRow`.
- `String.toOrgDocument()` (line 93) — single-pass line scanner producing a **flat** list of
  children. `OrgSection` carries a `level`, but sections are not nested: the document is a
  flat sequence, and a section's body is an already-trimmed `content: String`.
- `OrgNode.toOrgString()` (line 329) — pretty-printer that regenerates Org text from the AST.
- `parseTodoConfigFrom` (line 273) — reads `#+TODO:` lines into `OrgTodoConfig`.
- `String.toggleNthCheckbox` (line 305) — flips the Nth checkbox directly in raw text
  (added by the in-flight fix, see below).
- No source positions on any node. No `kotlinx-datetime` dependency in this module.
- Test coverage is good for what exists: 12 test files covering parsing of lists, tables,
  planning lines, metadata, paragraphs, printing, checkboxes.

Constructs the parser does **not** model: heading nesting/hierarchy, drawers
(`:PROPERTIES:`/`:END:`), blocks (`#+BEGIN_SRC`…), nested/indented lists, ordered lists,
priorities (`[#A]`), inactive timestamps `[…]`, timestamp ranges, repeaters, `CLOSED:`,
description lists, footnotes.

### A second, divergent grammar in the UI

`composeApp/src/commonMain/kotlin/com/korgnote/app/ui/OrgSyntaxHighlighting.kt` (504 lines)
contains an independent line-level Org grammar — the `OrgSyntax` object (line 85) — with
regexes for headings, blocks, keywords, comments, drawers, properties, planning,
horizontal rules, list items with checkboxes, description terms, priorities and tags, plus
inline handling of links, URLs, timestamps, footnotes, statistics cookies and emphasis.

**It knows about more Org syntax than the actual parser in `shared/` does.** The two are
maintained separately and can disagree.

`OrgSyntaxHighlightTransformation` (line 481) is a `VisualTransformation` with
`OffsetMapping.Identity` that re-highlights the *entire document* on every keystroke.

### `server/` — Ktor

`server/src/main/kotlin/com/korgnote/server/Application.kt`

- Binds `0.0.0.0:8080` (line 36). No authentication, no TLS, no compression plugin.
- Logging interceptor on every call (line 43).
- `loadOrgFiles()` (line 81) walks `NOTES_DIR` (or `notes.dir`, default `notes`) once and
  reads every `.org` file into `val orgContents` (line 112). **The map is never refreshed
  from disk**; only `POST` mutates it (line 189).
- `GET /api/org-content` (line 169) re-parses *every* document on *every* request and
  returns the whole corpus as JSON.
- `POST /api/content/{name...}` writes the received body verbatim, restricted to paths
  already present in the startup map — which incidentally prevents path traversal and
  also prevents creating new files.
- Falls back to bundled `resources/org` when the notes directory does not exist, in which
  case saving returns 403.

### `composeApp/` — client

- `FileTreeViewModel` (259 lines) is the only ViewModel and holds all state: file tree,
  filter query, every parsed document, the selected file's edit buffer, the schedule index
  and the error banner.
- `KtorOrgRepository` — four calls: `fetchAllContents`, `fetchTree`, `fetchRawContent`,
  `saveContent`.
- Persistence is limited to server host/port (`java.util.prefs` on JVM, `NSUserDefaults` on
  iOS, SharedPreferences on Android). **No document is persisted on the device.**
- On any load failure, `loadFromServer()` clears all state and shows an error (line 191ff):
  with no network there is no app.
- `OrgText.kt` (522 lines) renders the AST into a plain `Column` (line 88) — every node of
  the document is composed at once; `App.kt` wraps the screen in
  `verticalScroll(rememberScrollState())`.
- `CalendarScreen.kt` (469 lines) — month grid and week row with swipe navigation and a
  per-day dialog. Its only input is `scheduledByDate`, built in `FileTreeViewModel:230` by
  scanning for `OrgPlanning` nodes whose `planType == "SCHEDULED"` and whose timestamp
  matches `<(\d{4})-(\d{2})-(\d{2})`.

### In-flight, uncommitted change

The working tree carries an uncommitted fix (branch `worktree-fix-org-raw-text-edit`, PR #1):
the edit buffer is now loaded from `GET /api/content/{path}` as raw text instead of from
`doc.toOrgString()`, and checkbox toggling patches the raw text via
`String.toggleNthCheckbox` instead of re-serialising the whole document. It exists because
`toOrgString()` is lossy: opening a file and saving it unchanged rewrote blank lines and
trailing whitespace on disk.

That fix removes the symptom at the edit-buffer level. The underlying cause — a model that
cannot reproduce its own source text — is still present, and is the subject of
[04-org-core.md](04-org-core.md).

## Known defects and limitations

| # | Issue | Where | Impact |
|---|-------|-------|--------|
| D1 | Server caches files at startup and never re-reads disk | `Application.kt:81,112` | Edits made in Emacs or arriving via Syncthing are invisible until the server restarts; a `POST` can silently overwrite them with stale content |
| D2 | Whole corpus is parsed and shipped as JSON on every cold start | `Application.kt:169` | Bandwidth and CPU grow with corpus size; directly opposes G5 |
| D3 | No local persistence of documents on the client | `composeApp/…/data/` | Offline use (G4) is impossible today |
| D4 | AST cannot round-trip to its own source text | `OrgMode.kt:329` | Caused the whitespace-rewriting bug; blocks structural editing |
| D5 | Two independent Org grammars | `OrgMode.kt` vs `OrgSyntaxHighlighting.kt:85` | Rendering, highlighting and parsing can disagree; every syntax addition is done twice |
| D6 | Full-document re-highlight per keystroke; whole document composed eagerly | `OrgSyntaxHighlighting.kt:481`, `OrgText.kt:88` | Editing large files will degrade; opposes G2 |
| D7 | Calendar only understands `SCHEDULED` with a plain date | `FileTreeViewModel.kt:230` | No `DEADLINE`, no ranges, no times, no repeaters; opposes G6 |
| D8 | No authentication, no TLS, binds `0.0.0.0` | `Application.kt:36` | Personal notes are readable and writable by anyone who can reach the port |
| D9 | No conflict detection on save | `Application.kt:189` | Last writer wins; concurrent edits from two devices lose data |
| D10 | No compression on server responses | `Application.kt` | Org text compresses ~4–5×; free bandwidth win being left on the table |
| D11 | Cannot create, rename or delete files from the app | `Application.kt:189` | Capture/new-note workflows are impossible |
| D12 | Flat document model (sections are not nested) | `OrgMode.kt:19,93` | Subtree operations (fold, promote, filter by ancestor tag) cannot be expressed |
