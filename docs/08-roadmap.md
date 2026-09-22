# 08 — Roadmap

Tasks are sized for one working session each and ordered so that the app keeps working
after every one. Dependencies are explicit; anything without a shared dependency can be
done in any order.

**If you only do three things:** ~~T1~~, ~~T3~~ (the model is lossless) and ~~T8–T10~~
(local-first sync) — all done. Those three were the architecture; everything still open is
improvement on top of it.

Phase 1 (the Org core, T3–T7) is complete: the model is source-mapped and lossless, headings nest,
the grammar covers the constructs these notes actually use, Org syntax is defined in one place, and
edits are range replacements. Phase 2 (T8–T11) is complete too: the app holds the whole corpus
locally, syncs through the v1 API, and merges divergent edits instead of losing them. **Phase 3 is
complete** as well — the editor work is done bar what was deliberately deferred, and search and
completion have landed. Next is **Phase 4** (agenda, T15–T18), whose first task is the timestamp
parser that completion is already writing timestamps for.

## Status

Updated 2026-09-22.

| | Task | State |
|---|---|---|
| ✅ | T12a Editor performance diagnostic | Done — [notes/editor-perf.md](notes/editor-perf.md) |
| 🟡 | T12b Editor surface | Done bar two items deferred on purpose — see the task |
| 🟡 | T0 / T0b Text-editing spikes | Answered in practice, findings in [06](06-editor.md#editor-surface) |
| ✅ | T1 Server reads the disk | Done — `FileStore`, plus conflict refusal and path validation |
| ✅ | T3 Source-mapped documents | Done — `range` on every node, `text` on the document, raw-text wire |
| ✅ | T4 Nested headings | Done — sections own their body and sub-headings; subtree = one range |
| ✅ | T5 Complete the grammar | Done — blocks, drawers, lists, priorities; `OrgSyntax` now shared |
| ✅ | T6 One grammar | Done — `orgSpans()` in shared; no Org pattern left in `composeApp/` |
| ✅ | T7 Structural edit API | Done — `org.edit`; ordinals replaced by node identity |
| ✅ | T2 Transport hygiene | Done — gzip, bearer token, loopback default, LAN opt-in for testing |
| ✅ | T8 Replica store | Done — local replica; the app opens and edits with the server unreachable |
| ✅ | T9 Sync API v1 | Done — manifest, conditional files, bundle, SSE fed by a file watcher |
| ✅ | T10 Sync engine and cutover | Done — old API deleted; the client syncs through v1 |
| ✅ | T11 Conflicts | Done — diff3 merge; divergent edits keep both versions, never markers |
| ✅ | T13 Completion | Done — pure engine, every trigger row tested; a suggestion strip, not a popup |
| ✅ | T14 Search | Done — linear scan over the replica, words and `tag:`, results open at the match |
| 🟡 | T10b Web client | Mostly wired by T10; what is left is listed in the task |
| ❌ | T15–T18 | Not started |

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

### T2 · Transport hygiene — ✅ **done**
**Landed** Ktor `Compression` (gzip/deflate), bearer-token auth on `/api` from `KORGNOTE_TOKEN`, and
a bind host chosen by configuration: loopback by default, all interfaces once a token is set. The
token is a field in the app's settings and rides on every request. README documents the variables
and TLS via a reverse proxy.
**The testing escape hatch** `KORGNOTE_ALLOW_LAN=1` serves the LAN with **no** token. Without it the
safe default — loopback only — would break testing against a phone, which is how this app is actually
developed; requiring a token there means re-entering it on every device after every reinstall. It is
opt-in, and the server logs one line on startup naming which of the three modes it is in, including
the words "NO authentication" when that is what is happening.
**Verified** 11 `TransportSecurityTest` cases: where it binds in each configuration, that an
unauthenticated or wrongly-authenticated `/api` request is refused while the right one is accepted,
that writes are protected as well as reads, and that responses are gzipped when asked for and not
when they are not. 4 `KtorOrgRepositoryAuthTest` cases prove the client actually puts the token on
the wire — on every call — and sends no header at all when the token is blank, using a mock engine
rather than trusting the code by reading. Removing the loopback default fails 2 tests.
**Not verified by socket** The bind address is covered by unit tests and one line of wiring in
`main`; I did not start a second server to watch it bind, because port 8080 was in use by the
running instance.
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

### T5 · Complete the grammar — ✅ **done**
**Landed** `OrgSyntax` moved from `composeApp/` into `shared/`, so the parser and the highlighter
recognise Org from one set of patterns instead of two — the first half of T6's "one grammar". The
parser gained blocks, drawers, comments, horizontal rules, footnote definitions, description items,
ordered lists, nested lists and heading priorities. Blocks and drawers are **opaque**: their contents
are kept as text and never parsed, because a `* heading` inside a source block is code. `OrgSection`
gained `priority`; list items became `OrgListItem`/`OrgCheckboxItem` that can own a nested list.
Unknown constructs still fall through to `OrgRaw`, never to an error.
**Not included** Inline constructs — emphasis, links, inline timestamps, footnote *references* — are
span-level, not block-level, and belong with `spans()` in T6. Only footnote *definitions* (a line
starting `[fn:x]`) are nodes here. Blank lines still belong to no node, so I3 keeps the weaker form
T4 recorded.
**Verified** 80 shared tests, including `OrgGrammarTests` covering each construct plus a round-trip
corpus. Over the real notes directory: 366 files, 0 round-trip failures, 0 range violations, 0
nesting violations, 0 parser/splitter disagreements. Compiles for JVM, wasm, iOS and Android.
**Found by the real corpus, not by the tests** The list grammar matches `*` as a bullet, so a plain
list swallowed every heading that followed it — 272 headings across the real notes, and 23 files where
the element splitter and the parser then disagreed about where a heading started, which is the
condition for an edit to splice over the wrong range. A hand-written corpus missed it because the two
constructs have to be adjacent to trigger it. `aListStopsAtTheNextHeading` and
`theSplitterAgreesWithTheParserOnTheNewConstructs` now pin it down.
**Depends** T4
**Scope** Drawers, blocks, ordered and nested lists, priorities, inactive timestamps,
description lists, footnotes — porting regexes from `OrgSyntax` rather than inventing new
ones. Unknown constructs must parse as preserved raw text, never as errors.
**Done when** A corpus round-trips losslessly and the new constructs appear in the tree.

### T6 · One grammar — ✅ **done**
**Landed** `orgSpans(text)` and `orgSpansForLine(line, state)` in `shared/` say what a stretch of Org
text *is*, as `OrgSpan(range, kind, nesting)`; `composeApp/` only maps a kind to a colour. The
editor's `OrgSyntaxHighlightTransformation` now paints from those spans, and **no Org pattern remains
anywhere in `composeApp/`** — the last two, `#+TITLE:` and the timestamp date, moved to `OrgSyntax`
with the rest. That is D5 closed. `OrgLineState` is public so a client keeping a per-line cache can
thread the state itself, which is what let the editor's incremental cache survive the change
untouched.
**Not included** Reading mode still walks the line itself. It does not just style text, it *rewrites*
it — hiding emphasis markers and link targets, turning a `LOCATION` property into a Google Maps link
— and a list of spans over the original text cannot express a text substitution. Spans could grow a
"replace this range with this text" facet; until then the reading renderer consumes the same shared
patterns but keeps its own walk. The *grammar* is unified; the second *renderer* is not.
**Verified** All 23 `OrgSyntaxHighlightingTest` cases pass unchanged — they were the contract — plus
the 8 cache, 8 viewport, 4 whitespace and 5 metrics tests, so the editor's incremental path and the
zero-width-whitespace-run invariant both still hold. 14 new `OrgSpansTests` cover the span layer
directly. Breaking it (emphasis spanning its markers instead of its content) fails 2 highlighting
tests, so the new layer is load-bearing rather than decorative.
**Depends** T5
**Scope** Add `OrgDocument.spans(range)`; rewrite `OrgSyntaxHighlightTransformation` to use
it; delete the duplicate grammar in `OrgSyntaxHighlighting.kt`, keeping `OrgColors` and the
existing highlighting tests as the contract.
**Done when** Highlighting output is unchanged for the existing test cases and Org syntax
is defined in exactly one place (D5).

### T7 · Structural edit API — ✅ **done**
**Landed** `com.korgnote.common.org.edit` with one primitive — `replace(range, replacement)`, which
is invariant I4 — and every operation built on it: `toggleCheckbox`, `setCheckbox`, `setTodo`,
`cycleTodo`, `setTags`, `setPlanning`, `promote`, `demote`. Because they all reduce to a range
replacement, the rest of the file is untouched *by construction* rather than by care: the bug that
started this whole line of work, where saving rewrote blank lines nobody had edited, is now
unrepresentable. `promote`/`demote` are subtree-aware — T4 made a heading's range cover its subtree,
so moving a heading takes its children with it in a single replacement — and they leave stars inside
a source block alone, because those are code.
**The ordinal path is gone.** `String.toggleNthCheckbox`, `orgCheckboxesBefore` and
`toggleCheckboxByOrdinal` are deleted. Reading mode now hands back the `OrgCheckboxItem` it rendered
and the view model edits the node at that offset. This also removed the O(n²) checkbox counting the
reading view did per element (12 ms on the biggest real file), since nothing counts boxes any more.
**Verified** 24 `OrgEditTests`, each of the before/after shape the roadmap asked for, most asserting
that *exactly one line* changed and the line count did not. Two cases were ported from the deleted
ordinal tests rather than dropped. Exercised across the real notes directory: 366 files, 127
checkboxes and 2397 headings, every toggle changing exactly one line, every `setTodo` and `cycleTodo`
preserving the line count, and every demote/promote round-tripping to the original bytes. Breaking
block-awareness in `demote` fails a test, so the guard is load-bearing.
**Note** The one apparent failure in that sweep was the verification script's own fault: it located
the demoted heading by title and level, which is ambiguous when two headings share a title. Locating
it by range — node identity, the thing T7 exists to provide — there is no failure. A neat illustration
of why ordinals and titles were the wrong handle.
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

### T8 · Replica store — ✅ **done**
**Landed** `ReplicaStorage` with two implementations — `FileSystemReplicaStorage` (written once in a
`jvmSharedMain` source set that JVM and Android both use, plus a separate iOS one over
`NSFileManager`) and `InMemoryReplicaStorage` for the web and for tests. `DocumentStore` sits on top
and is the only writer: documents, dirty flags, change events, and the `base/` copies a three-way
merge will need in T11. Writes are atomic (temp file then rename on JVM, `writeToFile(atomically)` on
iOS), and a corrupt `meta.json` is discarded rather than allowed to make the notes unopenable.
**The app now reads and writes locally.** `FileTreeViewModel` renders from the replica and saves to
it first; the network only refreshes it. A failed load used to clear the tree "to avoid showing stale
content", which meant an unreachable server left the app empty — now it costs freshness, not access.
A failed save leaves the file dirty instead of losing the edit. All four entry points bind their own
storage: a real directory on desktop, Android and iOS; memory on the web.
**Verified** One `DocumentStoreContract` runs against both implementations — 9 cases in memory, the
same 9 plus 4 filesystem-specific ones against a real directory — which is what makes the in-memory
store a fair stand-in for the web build. `OfflineReplicaTest` covers the acceptance criteria at the
app level: the app opens its notes with the server unreachable, an edit made offline stays dirty and
survives a "restart" (a fresh view model over the same replica), and reconnecting does not overwrite
it. Removing the dirty-file guard fails that last test in **both** implementations.
**Not done here** Nothing pushes yet: dirty files stay dirty until the sync engine in T10. The
replica is populated by the existing `/api/org-content` call, as this task specifies.
**Depends** —
**Scope** `ReplicaStorage` interface plus two implementations: `FileSystemReplicaStorage`
(`notes/`, `.korgnote/base/`, `meta.json` under a platform app-data directory via
`expect/actual`, Okio or `kotlinx-io`) and `InMemoryReplicaStorage` for web and tests.
`DocumentStore` on top: documents, dirty flags, change events. The app reads and writes
*locally only* at this point; existing network calls stay in place to populate it.
**Done when** Native opens, renders and edits documents with the server unreachable and an
offline edit survives a restart; the same `DocumentStore` tests pass against the in-memory
implementation with no filesystem involved.

### T9 · Sync API v1 (server) — ✅ **done**
**Landed** `/api/v1/manifest[?since=]`, conditional `GET|PUT|DELETE /api/v1/files/{path…}`, a
streamed NDJSON `/api/v1/bundle[?since=]` with a size cap, and `/api/v1/events` as SSE. `FileStore`
gained create (`If-None-Match: *`) and delete-to-trash: a deleted note moves to
`.korgnote-trash/<date>/` rather than being unlinked, which makes a sync bug recoverable. A
`ChangeJournal` holds the generation counter and a bounded log; `NotesWatcher` feeds it from a
`WatchService`, so an edit made in Emacs becomes a generation bump and an SSE event. The old
endpoints are untouched until the client cuts over in T10.
**Design note** A stale `If-Match` answers **409**, not 412, and includes the current `ETag`: this is
a mergeable conflict, not a precondition the client should give up on. The journal is deliberately an
optimisation — a `since` it cannot cover returns a full manifest — which is what makes it safe to
hold only in memory across restarts.
**Also added** `KORGNOTE_PORT`, so a second server can run beside the real one while testing.
**Verified** 34 server tests (14 API, 7 journal, 2 watcher, plus the existing suites). The watcher
test prints its measured latency; an external edit was detected in **110 ms**, comfortably inside the
"within seconds" the protocol promises. Then every acceptance criterion was checked with `curl`
against a real server, which is how the task phrased them: manifest listed, `If-None-Match` → **304**,
stale `If-Match` → **409** with the file untouched on disk, current `If-Match` → 200, bundle as
NDJSON, `?since` returning only the delta and falling back to a full manifest for an unknown
generation, and — writing files directly on disk, the Emacs case — two `event: changed` frames
arriving on the SSE stream.
**Not covered by an automated test** The SSE stream itself. Driving a never-ending response through
the Ktor test host deadlocked the test rather than exercising the server, so it is verified by curl
above and by the journal and watcher tests underneath it, rather than by a flaky test pretending to
be evidence.
**Depends** T1
**Scope** `/api/v1/manifest`, `GET|PUT|DELETE /api/v1/files/{path…}` with ETag semantics,
`GET /api/v1/bundle` (streamed NDJSON, for clients that start empty), `/api/v1/events` SSE
fed by a `WatchService`. Old endpoints remain for now.
**Done when** `curl` can list the manifest, fetch with `If-None-Match` and get a 304, push
with `If-Match` and get a 409 on a stale write, and receive an SSE event when a file is
touched by Emacs.

### T10 · Sync engine (client) and cutover — ✅ **done**
**Landed** `SyncEngine` in `shared/` implements the reconciliation table over `DocumentStore` and a
`SyncApi` interface; `KtorSyncApi` in the client is the HTTP half, including the SSE stream. The
view model now takes the engine instead of a repository, the file tree is built from the replica, and
the app follows the event stream for the session, so an edit made in Emacs appears without a refresh.
**The cutover is complete.** `/api/org-content`, `/api/org-tree-paths` and `/api/content/{…}` are
deleted along with `KtorOrgRepository` and `OrgRepository` — verified by curl against a real server:
all three now answer **404** while `/api/v1/manifest` serves. Nothing in the client parses a
server-provided AST; it moves text and etags only (D2, D3).
**Not included** Merging. A push refused with 409 keeps the local version and flags it
(`conflictedPaths`), and the UI says so. Three-way merge and conflict copies are T11. Every branch of
the table is written so that no edit is ever discarded in the meantime — including the awkward row
where a file was deleted on the server but edited here, which re-pushes it.
**Verified** 14 `SyncEngineTests`, one per row of the table plus the two event-stream cases, using a
fake server that counts requests — which is how "a cold start transfers only what it lacks" is
asserted rather than asserted-about: the second cycle fetches **nothing**. Removing the conflict
branch fails the test that protects a local edit. The server's own suite was ported to v1 rather than
deleted, so the notes-directory resolution, tilde and env expansion, and external-edit protection
keep their coverage — now expressed with explicit `If-Match` instead of the server inferring a
baseline.
**Depends** T8, T9
**Scope** `SyncEngine` implementing the pull/push table
([05](05-sync-protocol.md#sync-algorithm)) without merge yet (conflicts are deferred to
T11: on 409, keep local and flag). Wire the triggers. Build the file tree from the replica.
Delete `/api/org-content`, `/api/org-tree-paths`, `/api/content/{…}` and
`KtorOrgRepository`.
**Done when** Cold start transfers a manifest plus only stale files; a change made in Emacs
appears without restarting anything; nothing in the client parses server-provided ASTs any
more (D2, D3).

### T10b · Web client on the same engine — 🟡 **mostly wired by T10**
**Already true** The wasm target builds and runs on the shared stack: `InMemoryReplicaStorage`,
`InMemorySettingsRepository`, the same `SyncEngine` and ViewModel as the native clients, and a
same-origin base URL from `window.location.origin`, so no host or port is configured on web.
**Still to do** Cold start through `GET /api/v1/bundle` — the server serves it, but `SyncApi` has
no `bundle()` yet, so every client still walks the manifest and fetches file by file; debounced
(~1 s) autosave; and confirming the SSE loop, which reads the stream with `prepareGet`, actually
delivers on the JS engine. The no-op `NotificationScheduler` belongs with T18, which is what
introduces the interface.
**Depends** T10
**Scope** Wire the wasm target to `InMemoryReplicaStorage` + `GET /api/v1/bundle` on page
load, debounced autosave (~1 s) through the same `SyncEngine`, SSE for live updates,
same-origin base URL (no host/port setting needed on web), no-op
`NotificationScheduler`. Optional `localStorage` draft per open document.
**Done when** The browser build edits and saves through the v1 API, reflects a change made
in Emacs within seconds, and shares 100% of its ViewModel, Org-core and sync code with the
native clients.

### T11 · Conflicts — ✅ **done**
**Landed** `shared/sync/merge.kt`: a line-based three-way merge (diff3) over base, local and remote,
dependency-free and with the timestamp injected so it can be tested without a clock. `SyncEngine`
now reconciles instead of only flagging — a clean merge is written locally and pushed with the
*remote's* etag, and a real conflict keeps the local version live while the server's is saved beside
it as `name.sync-conflict-<timestamp>.org`, mirroring Syncthing's naming. The conflict copy is itself
a note, so it is pushed and the other device sees it too. The banner names the file and says where
the other version went.
**One deliberate departure from diff3** When both sides only *added* lines at the same point, both
additions are kept rather than declared a conflict. Strict diff3 conflicts there, rightly for source
code where order can matter. For a list of Org headings it does not, and it is the commonest case
there is — capturing a TODO on the phone and another on the laptop. Keeping both loses nothing;
conflicting would litter the notes with copies for the most ordinary thing a person does.
**Never** are conflict markers written into a note. A file full of `<<<<<<<` is a corrupted note, and
the merge has no code path that can produce one.
**Verified** 12 `MergeTests` and 18 `SyncEngineTests`, covering both acceptance criteria directly:
edits to different headings converge with nothing asked of the user, and the same line changed twice
produces a conflict copy while the local version stays live. Also that the same conflict is not
re-reported every cycle, and that the copy reaches the server. Replacing the merge with an
unconditional conflict fails the convergence test.
**Found while testing** The fake server in `SyncEngineTests` derived etags from content *length*, so
two different edits of equal length looked identical to it and the conflict path was never reached.
Etags now follow content. A fake that is too forgiving hides exactly the case the tests exist for.
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

**Landed since:** lazy reading mode and the single-scroll-owner restructuring. `OrgDocumentView` is
a `LazyColumn` keyed by element, which took first composition of the largest real file from 234 ms
to 38 ms, and `App.kt` hands its scroll to whichever component owns it — reading mode, and now the
search results — while keeping it for the whole-file editor and every other screen. Measurements
and the reasoning are in [09](09-element-editing.md#known-gaps).

**Still outstanding, both deferred on purpose:**
- Read↔edit scroll mapping for the *whole-file* editor. Per-element editing removed the transition
  that needed it, so this is only worth doing if the whole-file editor stops being an escape hatch.
- Debounced, cancellable re-parse. Not needed at current costs; revisit if it regresses.

### T13 · Completion — ✅ **done**
**Landed** `shared/org/complete/OrgComplete.kt`: `complete(doc, cursor, ctx)` returns items that
each carry the range of typed text they stand in for and where the caret should land, so accepting
one is a range replacement like every other edit here — `applyTo` performs it and is what both the
editor and the tests call. All eight trigger rows are implemented, tried in a fixed order so that
`[[` is a link before it is a bracket and `<s` is a block template before it is a timestamp.
**A strip, not a popup.** The design sketched a floating popup for desktop and a strip for phones.
Both platforms get the strip: the popup fights the IME and the selection handles, phones are where
these notes are mostly written, and one presentation is one thing to keep working. Desktop loses
nothing — the field handles Tab to accept, arrows to move and Esc to dismiss, so a hardware
keyboard never has to reach for a chip. Tab rather than Enter, because Enter is a newline in a
multi-line element and stealing it would make an ordinary edit depend on whether a chip happened to
be showing.
**Context comes from outside the document** The engine is handed one element while the useful
material is corpus-wide, so tags (most-used first), file paths, the open file's `#+TODO:` keywords
and today's date arrive in a `CompletionContext` the view model rebuilds when the replica or the
open file changes — never per keystroke. Walking all 366 real notes for the tag counts costs 3 ms
warm, 14 ms cold, so nothing cleverer is warranted.
**Verified** 26 `OrgCompleteTests` — one or more per trigger row, asserting the text and caret the
user ends up with rather than the shape of the item — and 5 `CompletionContextTest` cases for the
material the app supplies. The acceptance criterion is a test by name: typing `* ` then `TO` offers
the document's own `#+TODO:` keywords, and a document naming none falls back to Org's.
**Found while writing it** The editor spliced items with `insert.length` instead of the item's
`caretOffset`, which would have left the caret after `#+END_SRC` instead of inside the block. That
is why the splice now lives in the engine as `applyTo`, where a test can reach it.
**Depends** T12b
**Scope** `org.complete` engine plus popup (desktop) / suggestion strip (mobile), covering
the trigger table in [06](06-editor.md#completion-g3).
**Done when** Each trigger row has a unit test, and typing `* ` then `TO` offers the
document's own `#+TODO:` keywords (G3).
**Not included** The whole-file editor, which still has no completion: it holds a `String` rather
than a `TextFieldValue`, so it has no caret to complete at, and it is an escape hatch rather than
the path edits take. Wiring it is the same call plus that conversion.

### T14 · Search — ✅ **done**
**Landed** `shared/org/search/OrgSearch.kt`: `SearchQuery.parse` turns what was typed into words
and `tag:` terms, and `searchOrg` scans the replica linearly, returning hits that carry the file,
the heading breadcrumb, the line number and the character offset. A new Search screen lists them
and opens one at the element holding the match, reusing the offset→element lookup the calendar
jump already needed. No index, no new dependency.
**Tags are inherited**, as they are in Org: a tag on a parent heading, or in `#+FILETAGS:`, counts
for everything beneath it — without that, `tag:work` would miss most of what a person means by it.
`tag:work` on its own lists the headings carrying the tag; with words, it narrows where they are
looked for. `:work:` is accepted as typed, since that is how a heading spells it.
**`filterQuery` really did become a special case**: the drawer's filter now parses the same query,
so it still matches file names, and `tag:work` there lists the files with work in them. It is
recomputed when the query or the replica changes rather than on every recomposition, because a tag
term has to look inside the files while the drawer is redrawn constantly.
**Verified** 16 `OrgSearchTests` and 7 `SearchTest` cases: offsets that point at the match in the
file, breadcrumbs, line numbers that survive skipped elements, tag inheritance from both sources,
words narrowing rather than widening, quoted phrases, a heading inside a `#+BEGIN_SRC` block not
being treated as a heading, and the scan still finding its one match in a ~4 MB corpus, larger than
the real notes directory.
**The speed criterion is measured, not asserted.** A `measureTime` assertion went in first and came
back out: a clock in a test suite asserts about the machine running it, so it fails on a loaded
laptop while the code is unchanged. The corpus-sized test now prints its time and checks only the
result; an accidentally quadratic scan would announce itself by never finishing, which is not a
failure anyone can mistake for a slow machine.
**Measured on the real notes** 366 files, 1.21 MB: a word takes 17–54 ms, a tag query 8–15 ms, and
a tag narrows a word search as it should (`cuisine` 66 hits, `tag:house cuisine` 61). The synthetic
4 MB corpus scans in ~200 ms. Which is the whole argument for the linear scan: the index this would
have needed is a thing to keep correct on every save, and it would save tens of milliseconds.
**Found while testing** Search results and the agenda index went stale after a local save: both are
derived from the in-memory documents, and only the agenda one was being rebuilt. They are refreshed
together now, from one place, since forgetting one of them is invisible until someone reads results
describing a document as it used to be.
**Depends** T8
**Scope** Linear-scan full-text search over the replica with heading breadcrumbs and jump-to-offset;
`filterQuery` becomes a special case.
**Done when** Searching the whole corpus returns results in well under a second offline.
**Not included** The structured query grammar (`todo:NEXT`, `deadline:<=7d`) and saved searches.
The parser has one prefix today; adding another is a case in `SearchQuery.parse` and a predicate in
`searchOrgFile`, which is where agenda filters should meet it (T16).

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
