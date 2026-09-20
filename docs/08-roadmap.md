# 08 — Roadmap

Tasks are sized for one working session each and ordered so that the app keeps working
after every one. Dependencies are explicit; anything without a shared dependency can be
done in any order.

**If you only do three things:** ~~T1~~ (done), ~~T3~~ (done — the model is lossless),
and T8–T10 (local-first sync). Everything else is improvement; those three
are the architecture.

## Status

Updated 2026-09-20.

| | Task | State |
|---|---|---|
| ✅ | T12a Editor performance diagnostic | Done — [notes/editor-perf.md](notes/editor-perf.md) |
| 🟡 | T12b Editor surface | Partly — see the task for what landed and what did not |
| 🟡 | T0 / T0b Text-editing spikes | Answered in practice, findings in [06](06-editor.md#editor-surface) |
| ✅ | T1 Server reads the disk | Done — `FileStore`, plus conflict refusal and path validation |
| ✅ | T3 Source-mapped documents | Done — `range` on every node, `text` on the document, raw-text wire |
| ✅ | T4 Nested headings | Done — sections own their body and sub-headings; subtree = one range |
| ❌ | T2, T5–T7, T8–T11, T13–T18 | Not started |

**Delivered but never planned: per-element editing** ([09](09-element-editing.md)). It came from
how the app is actually used — most edits add one TODO — and dissolved the performance problem
instead of solving it, retiring the block-virtualisation branch of T12b along with its UX tail. It
also establishes the range-based text-edit principle that T7 was meant to introduce.

Two prerequisites turned out to be wrong and have been corrected in [06](06-editor.md):
`TextFieldState` cannot carry styling, and editor styling cannot change text metrics.

---

## Phase 0 — Urgent and de-risking

### T0 · iOS text-editing spike
**Goal** Find out whether Compose Multiplatform's iOS text field can carry this product
before investing in editor features.
**Scope** Throwaway branch. A `BasicTextField` with `TextFieldState` holding a 200 KB Org
file on a real device: scrolling, caret placement, selection handles, autocorrect,
external keyboard, a popup anchored at the caret.
**Done when** A written verdict in `docs/notes/ios-text-spike.md`: usable as-is / usable
with workarounds / not viable. If not viable, stop and re-plan before T12b–T13.
**Why first** It is the only finding that could change the stack, and it is cheap now.

### T0b · Web text-editing spike
**Goal** Same question as T0, for the browser: is Compose/wasm's canvas-rendered text
field good enough to keep the web build as a *full editing* client?
**Scope** Throwaway. Open a large Org file in the current web build (or a `BasicTextField`
harness) and exercise: caret placement, selection and drag-select, clipboard, IME/dead
keys, browser shortcuts, scrolling a long document, and behaviour on a mobile browser.
**Done when** A verdict in `docs/notes/web-text-spike.md`. The architecture work (T8–T10)
does not depend on the answer — only the decision to keep editing on web does.

### T1 · Server reads the disk, not a boot snapshot — ✅ **done**
**Landed** `FileStore` answers from disk on every request, hashing content for ETags with an
`(size, mtime)` cache so unchanged files are not re-read. Writes are atomic (temp file + move) and
refuse when the file changed underneath — checked against `If-Match` when the client sends one, and
otherwise against the version this server last handed out, which is what catches an edit made in
Emacs. Path validation rejects traversal, absolute paths and non-`.org` extensions. `/api/org-content`
caches parsed documents by content hash. API shape unchanged.
**Not included** The file watcher and SSE stream belong to T9. Two *app* clients racing on one file
is D9, and waits for T11.
**Goal** Fix D1 — the server currently answers from a map built at startup and can
overwrite edits made in Emacs or delivered by Syncthing.
**Scope** Introduce `FileStore` ([05](05-sync-protocol.md#server-a-file-store-that-tells-the-truth)):
on-demand directory walk, content hash with an `(path,size,mtime)` cache, atomic
write-and-rename, path validation. Rewire the existing endpoints to it.
Keep the current API shape — this is a behaviour fix, not the protocol change.
**Done when** Editing a file with an external editor while the server runs is reflected in
the next request; a save never clobbers a newer on-disk version silently; tests cover
external modification and path-traversal attempts.

### T2 · Transport hygiene
**Goal** D8, D10.
**Scope** Install Ktor `Compression`. Add bearer-token auth (`KORGNOTE_TOKEN`), add the
token to client settings. Bind `127.0.0.1` unless a token is configured. README section on
TLS via reverse proxy.
**Done when** Responses are gzipped, an unauthenticated request to a non-loopback listener
is refused, and the desktop/mobile clients still work.

---

## Phase 1 — Org core ([04](04-org-core.md))

### T3 · Source-mapped documents — ✅ **done**
**Landed** `OrgNode` became a `sealed class` carrying `range`, and `OrgDocument` carries `text`.
Both are body properties rather than constructor parameters, deliberately: these are data classes,
so a constructor property would join `equals` and every expected node in every parser test would
have to spell out exact offsets. Parsing fills them in; a node built by hand keeps an empty range.
The parser threads a `lineStarts` array and a `span(first, lastExclusive)` helper through every
construct. `/api/org-content` now returns raw text instead of a serialised tree, so the wire format
no longer pins the model — which is what lets T4–T7 reshape it freely. `@Serializable` is gone from
the node types.
**Not included** Table *cells* keep an empty range; locating them needs intra-line offsets, which
waits for the real table grammar in T5.
**Verified** `OrgSourceRangeTests` covers I1 and I2 over a corpus of edge cases (empty input, no
trailing newline, bare newlines, trailing whitespace, tags, tables, src blocks). The tests were
checked against two deliberate parser mutations — dropping the source, and an off-by-one in `span`
— and caught both. The 12 existing parser test files pass unchanged, and test sources compile for
JVM, wasm and iOS.
**Depends** —
**Scope** `OrgDocument` gains `text`; every node gains `range`. Parser records offsets.
No shape changes beyond that.
**Done when** Invariants I1/I2 hold as tests, the 12 existing parser test files still pass,
and a round-trip property test runs over the fixtures plus nasty whitespace cases.

### T4 · Nested headings — ✅ **done**
**Landed** `OrgSection` lost `content: String` and gained `children`, and headings nest by level, so
a section owns its body *and* its sub-headings. A heading's `range` therefore covers its whole
subtree: deleting or moving a heading with everything under it is one text replacement, which
`OrgNestingTests` demonstrates directly. `OrgNode` gained an open `children`, overridden by the
nodes that hold others (including a table's rows and a row's cells), so walking the tree no longer
needs a `when` over every type. Planning lines are now children of the heading they were written
under, which is what lets the agenda read a task's dates off the task itself instead of inferring
them from document order — `buildScheduledIndex` walks the tree and attributes a `SCHEDULED:` under a
sub-heading to that sub-heading, not to its parent. A table under a heading is a real node now
instead of being swallowed into the section's raw content string.
**Not included** I3 does not hold in its literal "every line belongs to exactly one leaf" form. A
heading line is owned by its section, which stops being a leaf once it has children, and blank lines
are owned by nothing. Both need nodes the grammar does not have — a heading-line node and whitespace
nodes — which is T5. What is tested is the strongest form that is true now and the one a renderer
needs: every non-blank line has exactly one *deepest* owner, and the nodes claiming it form a single
ancestor chain.
**Verified** All 58 shared tests pass, plus `OrgNestingTests`. The tests were checked against a
deliberate regression — making every heading close every open one, i.e. the old flat behaviour — and
5 of them failed, so they are load-bearing. Re-run over the real notes directory: 366 files, 0
round-trip failures, 0 range violations, 0 nesting violations, deepest tree 7 levels. Compiles for
JVM, wasm, iOS and Android.
**Depends** T3
**Scope** Sections own their bodies and sub-sections (D12); section `content: String`
becomes child nodes. Update `OrgText` to render the tree.
**Done when** Invariant I3 holds; rendering is unchanged visually; subtree operations are
expressible.

### T5 · Complete the grammar
**Depends** T4
**Scope** Drawers, blocks, ordered and nested lists, priorities, inactive timestamps,
description lists, footnotes — porting regexes from `OrgSyntax` rather than inventing new
ones. Unknown constructs must parse as preserved raw text, never as errors.
**Done when** A corpus round-trips losslessly and the new constructs appear in the tree.

### T6 · One grammar
**Depends** T5
**Scope** Add `OrgDocument.spans(range)`; rewrite `OrgSyntaxHighlightTransformation` to use
it; delete the duplicate grammar in `OrgSyntaxHighlighting.kt`, keeping `OrgColors` and the
existing highlighting tests as the contract.
**Done when** Highlighting output is unchanged for the existing test cases and Org syntax
is defined in exactly one place (D5).

### T7 · Structural edit API
**Depends** T4
**Note** Partly anticipated: `OrgElements.replaceOrgElement` already applies the "mutations are text
edits over ranges" principle for whole elements ([09](09-element-editing.md)). T7 extends it to
individual nodes, which needs source-mapped nodes from T3/T4.
**Scope** `org.edit`: `toggleCheckbox`, `setTodo`, `cycleTodo`, `setPlanning`, `setTags`,
`promote`, `demote` — all as range replacements. Replace the ordinal-based
`String.toggleNthCheckbox` and the `toggleCheckboxByOrdinal` path in the ViewModel.
**Done when** Each operation has a before/after text test proving only the intended line
changed, and the reader's checkbox tapping goes through it.

---

## Phase 2 — Local-first ([05](05-sync-protocol.md))

### T8 · Replica store
**Depends** —
**Scope** `ReplicaStorage` interface plus two implementations: `FileSystemReplicaStorage`
(`notes/`, `.korgnote/base/`, `meta.json` under a platform app-data directory via
`expect/actual`, Okio or `kotlinx-io`) and `InMemoryReplicaStorage` for web and tests.
`DocumentStore` on top: documents, dirty flags, change events. The app reads and writes
*locally only* at this point; existing network calls stay in place to populate it.
**Done when** Native opens, renders and edits documents with the server unreachable and an
offline edit survives a restart; the same `DocumentStore` tests pass against the in-memory
implementation with no filesystem involved.

### T9 · Sync API v1 (server)
**Depends** T1
**Scope** `/api/v1/manifest`, `GET|PUT|DELETE /api/v1/files/{path…}` with ETag semantics,
`GET /api/v1/bundle` (streamed NDJSON, for clients that start empty), `/api/v1/events` SSE
fed by a `WatchService`. Old endpoints remain for now.
**Done when** `curl` can list the manifest, fetch with `If-None-Match` and get a 304, push
with `If-Match` and get a 409 on a stale write, and receive an SSE event when a file is
touched by Emacs.

### T10 · Sync engine (client) and cutover
**Depends** T8, T9
**Scope** `SyncEngine` implementing the pull/push table
([05](05-sync-protocol.md#sync-algorithm)) without merge yet (conflicts are deferred to
T11: on 409, keep local and flag). Wire the triggers. Build the file tree from the replica.
Delete `/api/org-content`, `/api/org-tree-paths`, `/api/content/{…}` and
`KtorOrgRepository`.
**Done when** Cold start transfers a manifest plus only stale files; a change made in Emacs
appears without restarting anything; nothing in the client parses server-provided ASTs any
more (D2, D3).

### T10b · Web client on the same engine
**Depends** T10
**Scope** Wire the wasm target to `InMemoryReplicaStorage` + `GET /api/v1/bundle` on page
load, debounced autosave (~1 s) through the same `SyncEngine`, SSE for live updates,
same-origin base URL (no host/port setting needed on web), no-op
`NotificationScheduler`. Optional `localStorage` draft per open document.
**Done when** The browser build edits and saves through the v1 API, reflects a change made
in Emacs within seconds, and shares 100% of its ViewModel, Org-core and sync code with the
native clients.

### T11 · Conflicts
**Depends** T10
**Scope** `sync.merge` diff3; clean merges auto-resolve; conflicting ones produce
`name.sync-conflict-<timestamp>.org` and a UI banner (D9).
**Done when** Two devices editing different headings of one file converge with no user
action; editing the same line yields a conflict copy and never loses either version.

---

## Phase 3 — Editor ([06](06-editor.md))

### T12a · Editor performance diagnostic — ✅ **done**
**Depends** —
**Scope** One-line experiment: run with `visualTransformation = VisualTransformation.None`
and type in a 40 KB file on iOS and wasm. Record timings with and without, plus span count
and time spent in `filter`, in `docs/notes/editor-perf.md`.
**Why separate** It decides whether T12b is a day (highlighting scope) or a fortnight
(editor virtualisation). Slowness on 40 KB files is already observed, so this is
measurement, not speculation.
**Done when** The dominant cost — A (highlighting) or B (layout) — is written down with
numbers.

### T12b · Editor surface — 🟡 **partly done**
**Depends** T12a

**Landed:** per-line highlight cache with memoisation; viewport-scoped styling driven by
`onTextLayout`; monospace editor; `BasicTextField` with `OutlinedTextFieldDefaults.DecorationBox`;
`TextFieldValue` in the element editor for caret control; metric-neutral editor styling.

**Dropped as impossible:** the `TextFieldState` migration — that API has no styling hook
([06](06-editor.md#the-state-based-api-cannot-be-used)).

**Dropped as unnecessary:** block virtualisation — T12a came back as Cost A, and per-element editing
([09](09-element-editing.md)) removed document size as a concern.

**Still outstanding:**
- Lazy reading mode. `OrgDocumentView` renders every element eagerly in a `Column`; a `LazyColumn`
  keyed by element is the natural fix now that elements are the unit.
- The single-scroll-owner constraint in `App.kt`, and read↔edit scroll mapping for the *whole-file*
  editor. Per-element editing made this moot for the main path.
- Debounced, cancellable re-parse. Not needed at current costs; revisit if it regresses.

### T13 · Completion
**Depends** T12b
**Scope** `org.complete` engine plus popup (desktop) / suggestion strip (mobile), covering
the trigger table in [06](06-editor.md#completion-g3).
**Done when** Each trigger row has a unit test, and typing `* ` then `TO` offers the
document's own `#+TODO:` keywords (G3).

### T14 · Search
**Depends** T8
**Scope** Linear-scan full-text search over the replica with heading breadcrumbs and jump-to-offset;
`filterQuery` becomes a special case.
**Done when** Searching the whole corpus returns results in well under a second offline.

---

## Phase 4 — Agenda ([07](07-calendar-agenda.md))

### T15 · Timestamp parser
**Depends** T3 (`kotlinx-datetime` added to `shared/`)
**Scope** Full `OrgTimestamp`: times, time ranges, multi-day ranges, repeaters, warnings,
active/inactive.
**Done when** A table-driven test covers every row of the syntax table, including
malformed input degrading gracefully.

### T16 · Agenda engine
**Depends** T15, T5
**Scope** `AgendaEntry`/`AgendaOccurrence`, occurrence expansion including deadline warning
windows, repeaters, carried-over scheduled items, body timestamps. Replaces
`scheduledByDate`.
**Done when** Expansion rules are unit-tested over a fixture corpus, and the existing
calendar renders from the new index unchanged (D7).

### T17 · Day and week views
**Depends** T16
**Scope** Day view with an hour grid and "now" line; week view with all-day strip and hour
grid; month chips distinguishing deadline vs scheduled; agenda list view. Keep the current
swipe/animation behaviour.
**Done when** A day with two timed meetings, an all-day range and a deadline renders
correctly in all four views.

### T18 · Notifications
**Depends** T16
**Scope** `NotificationScheduler` `expect/actual` for iOS and Android; idempotent
"cancel all, schedule next N" honouring the iOS 64-pending limit; lead time and daily
digest settings.
**Done when** A timed entry produces a phone notification at the configured lead time with
the app closed and the network off.

---

## Sequencing notes

- T1 and T2 are independent of everything else and can ship immediately.
- Phase 1 (core) and T8 (replica store) can proceed in parallel — different modules.
- T8's storage interface is what keeps the web target cheap; do not let the filesystem
  implementation leak into `DocumentStore`, or T10b turns from ~120 lines into a fork.
- T10 is the cutover point: after it, the server no longer serves ASTs and the client is
  genuinely offline-capable. Everything downstream gets easier.
- The in-flight raw-text fix (PR #1 / uncommitted working-tree change) is superseded by T3
  and T7. Land it as-is first — it fixes real data corruption today — then let T7 remove
  the ordinal hack.
