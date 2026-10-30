# 01 — Current state (as-is)

> **Historical snapshot, 2026-09-20.** This describes the app as it was before the plan in these
> documents was carried out. Most of what it names is gone: the `/api/org-content`,
> `/api/org-tree-paths` and `/api/content` endpoints, `FileTreeViewModel`, `KtorOrgRepository`, the
> flat model, the boot-time file map. It is kept because 02 and 08 argue from it. For what exists
> now, read the [status table in 08](08-roadmap.md#status) and the [top-level README](../README.md);
> the defect table at the end says what became of each defect.

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

### Since this document was written

Three things landed the same day that change the picture:

- **Raw-text editing** — the edit buffer is the file's bytes, not a re-render of the AST (commit
  `c518b21`). This was the fix for the whitespace-rewriting bug and the reason D4 is mitigated.
- **Editor performance** — per-line highlight cache, memoisation, viewport-scoped styling, monospace
  editor. See [notes/editor-perf.md](notes/editor-perf.md) for measurements.
- **Per-element editing** — reading mode became the primary surface, with each heading editable in
  place as a range splice, plus adding sections. See [09-element-editing.md](09-element-editing.md).

Everything after that — the whole of the plan, and the work nobody planned (pairing, notes in a
folder or on WebDAV, widgets, tables, French) — is in [08-roadmap.md](08-roadmap.md) rather than
here. The editor-performance notes linked above were never committed; the finding (Cost A) is
recorded in [06](06-editor.md).

## Known defects and limitations

Status added 2026-09-20, brought up to date 2026-10-30 (1.4.1). Line references are to `2cc66f7`.

| # | Issue | Where | Impact |
|---|-------|-------|--------|
| D1 | Server caches files at startup and never re-reads disk | was `Application.kt:81,112` | **Fixed** — `FileStore` reads on demand and refuses a write whose baseline is stale |
| D2 | Whole corpus is parsed and shipped as JSON on every cold start | `Application.kt:169` | **Fixed** by T9/T10: the endpoint is deleted, and the client syncs text through the manifest, fetching only stale files — in one bundle when eight or more are stale |
| D3 | No local persistence of documents on the client | `composeApp/…/data/` | **Fixed** by T8: a local replica; the app opens and edits with no network |
| D4 | AST cannot round-trip to its own source text | `OrgMode.kt:329` | **Fixed** by T3: every node carries its source range and the document its text, so the model is lossless; edits are range replacements (T7) |
| D5 | Two independent Org grammars | `OrgMode.kt` vs `OrgSyntaxHighlighting.kt:85` | **Fixed by T5/T6, partly back.** `OrgSyntax` and `orgSpans()` in `shared/` are the one grammar, and `OrgElements.kt` uses them too. Since then, a few Org patterns have crept back into `composeApp/`: `OrgText.kt` (`:ID:`, `#+TODO:`), `NoteByline.kt` (`#+AUTHOR:`, `#+DATE:`), `LinkNavigator.kt` (`#+TITLE:`, link scheme), `OrgSyntaxHighlighting.kt` (tag name, date) and `TableFormulas.kt` (formula targets) |
| D6 | Full-document re-highlight per keystroke; whole document composed eagerly | `OrgSyntaxHighlighting.kt`, `OrgDocumentView.kt` | **Fixed.** Highlighting by cache and per-element editing; reading mode is a `LazyColumn` keyed by element (T12b) |
| D7 | Calendar only understands `SCHEDULED` with a plain date | `FileTreeViewModel.kt:230` | **Fixed** by T15–T17: a timestamp parser, an agenda engine (`AgendaIndex`) and agenda, day, week and month views |
| D8 | No authentication, no TLS, binds `0.0.0.0` | `Application.kt:36` | **Fixed.** T2 added a token; pairing replaced it (T30): devices sync over TLS on 8443 with a pinned certificate and a token each, and plain 8080 answers this computer only |
| D9 | No conflict detection on save | `Application.kt` | **Fixed** by T11: a diff3 merge, and a conflict copy when both sides changed the same lines; neither version is lost |
| D10 | No compression on server responses | `Application.kt` | **Fixed** by T2: gzip on |
| D11 | Cannot create, rename or delete files from the app | `Application.kt:189` | **Fixed** by T20: the drawer creates, renames and deletes notes, offline too, synced through tombstones ([05](05-sync-protocol.md#creating-renaming-and-deleting-t20)) |
| D12 | Flat document model (sections are not nested) | `OrgMode.kt:19,93` | **Fixed** by T4: headings nest, and a heading's range covers its subtree |
