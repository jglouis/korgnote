# 08 — Roadmap

What is still open, and why the code is the way it is. The subsystem documents (04–07, 09–11) say
what exists; this one records what was decided on the way, what it cost and what it measured, under
the task numbers (T1, T2, …) that code comments and tests still cite. The plan those numbers came
from, with its scopes and acceptance criteria, is in git.

Two things nobody planned changed the most. **Per-element editing** ([09](09-element-editing.md))
came from how the app is used, since most edits add one TODO, and dissolved the performance problem
instead of solving it, retiring the block-virtualisation branch of T12b and its UX tail. And two of
the plan's prerequisites turned out wrong: `TextFieldState` cannot carry styling, and editor styling
cannot change text metrics ([06](06-editor.md#editor-surface)).

## Open, and known gaps

This is the only status list; the rules for keeping it are in the [docs README](README.md).

**Not built, or not seen working**

- **Sync triggers.** Not built: iOS's `BGAppRefreshTask` (after T18 is seen on an iPhone) and a
  debounce on saves. A periodic poll while open is not wanted: the change stream's watchdog covers it.
- **T10b leftovers.** Debounced autosave, a web replica that survives a reload, SSE confirmed on the
  JS engine; and the web client works only on the server's machine.
- **T18 on an iPhone.** Reminders are built and have never been seen on a device.
- **Capture on an iPhone.** Seen on a simulator only; the rest of the iOS app is little tested on a
  real one.

**Behaviour that falls short**

- **A note in a dot-file.** `.plan.org` is in no dot-folder, so the server lists and serves it and it
  reaches a phone, while the folder and WebDAV sources leave dot-files out (`isListedNotePath`). The
  same file is a note through one source and not through another.
- **A dirty note in a dot-folder cannot sync.** The server refuses the path with a 422, so a phone with
  an unsent edit to a note it kept from before that rule, or one renamed into `.foo/`, reports it as
  failed every cycle. The text stays on the phone; nothing tells the person why.
- **A line merge counts adjacent lines as one change.** An element open in the editor is spared this
  ([09](09-element-editing.md#a-sync-during-an-edit)), but a tick beside a line that came in is refused,
  and an edit saved offline beside one becomes a conflict copy, though neither touched the other's line.
- **Turning the phone** closes an open New note dialog with its title, and reverts unsaved Settings
  values. Both are plain `remember`. Switching tab reverts those values too, with no warning.
- **A sync can close a dialog without a word.** A reschedule picker or a delete confirmation closes
  when a sync moves its entry or heading, rather than act on the wrong one. Nothing is changed, but
  the date chosen is lost and nothing says why.
- **Saving a new heading with nothing typed** writes `* ` to the file.
- **A shared address is cut at a bracket.** `https://example.com/a[1]` in shared text files `/a` as the
  link and `[1]` in the title (`firstUrlIn`).
- **A tap on body text just under a date link**, within its width, opens the calendar, not the editor.
  The line under a closed `:PROPERTIES:` drawer has the same trouble: the tap opens the drawer.
- **In Note details the keyboard covers Save**, so a tap there can land on a suggestion instead. In the
  element editor it covers the link picker's *Cancel* and *Web or file link…* the same way.
- **Android 7 cannot run the app.** On API 24 it stops as a source is chosen, with `NoClassDefFoundError
  java/time/ZoneOffset`, as it has since at least 1.2.0: `minSdk` says 24, and nothing desugars `java.time`,
  which arrives in API 26. API 25 has the same gap and was not run.
- **One note or folder the server cannot read stops every full sync**, with a `500` that names its path.
  Deliberate as far as what is listed goes: left out, it would go from every phone. Nothing tells the
  person which file it is.
- **A few changes on disk wait for the one-minute walk**: a file removed 50 ms after a dot-folder appears,
  as the first move to the trash does (1 in 12), and a folder delete now and then.
- **A signed-in API request with `..` in its path** can get a web client file (`/api/v1/../../composeApp.js`
  gives 200, not 404). No note is reachable that way. **`Host: [::1]`** gets 403 on the plain port.
- **A wrong WebDAV password met during onboarding** says "Check them in Settings", where there is no
  Settings yet.
- **The Agenda widget at two columns** gives half its width to the time, and a title breaks mid-word.
- **On the web, losing the server during a save or a sync** throws `MissingResourceException`, since the
  strings load lazily and cannot arrive. No "Cannot reach" appears and sync does not resume by itself; a
  tap on sync recovers it.
- **On iOS, closing the drawer on its scrim with Filter focused** leaves the keyboard up over the bottom
  bar.
- **On iOS, the Keychain outlives the app**, so a reinstall starts paired with the old server.
- **On the desktop, Week view at 800x600** clips Monday's column.
- **The delete confirmation shows a heading's raw tags**, as in `Soup :winter:`.
- **A first sync over the bundle's 8 MB cap writes what the bundle held only after the fetches**, so a
  kill partway downloads those notes again. It converges, at the cost of the bandwidth.
- **`todoKeywordsOf` still reads `#+TODO:` inside blocks**, where `todoLoggingOf` and `orgNoteTitle`
  know a block's text is not the note's.
- **`* Foo [x] bar` completes to a dead link.** A `[[*…]]` link keeps the title's brackets
  (`headingLinkTarget`), and a link cannot hold them.
- **A list whose first line is a bare `- ` parses as a paragraph.** The parser's main loop trims the
  line before it tries the list pattern, which wants a space after the bullet.
- **The morning digest** leaves out overdue items and deadline warnings
  ([07](07-calendar-agenda.md#notifications)).
- **Two kinds of range show on their first day only**: a done entry with a repeater and a
  `DEADLINE: <a>--<b>` ([07](07-calendar-agenda.md#agenda-engine)).
- **Completion labels** are English only ([06](06-editor.md#known-gap-suggestions-are-in-english)).
- **`L == R` is pushed again.** Two devices that converged write the same text once more, which bumps
  the generation ([05](05-sync-protocol.md#sync-algorithm)).
- **No `fsync` before a rename**, on the server or in the replica: a power cut can lose a save the
  filesystem had not flushed.
- **`ClockOutWorker` reads, then saves** with `save`, not `saveIfUnchanged`, so a pull landing between
  the two is written over.
- **The plain port** has no authentication: any process on the computer can use it. Another
  container on the Docker network cannot: it is refused by its address.

**Costs and code**

- **Two different 5,000-line files still build the full LCS table.** `alignment` matches what they
  share at either end, which covers an edit, and not two files with little in common: about 100 MB.
- **`search.refresh` and `completion.refreshContext` still scan on the main thread** during
  `rebuildDerived`; the links and the agenda went to the background.
- **`ElementRow` still has 20 parameters.**
- **`syncLocked` and `toOrgDocument` are long** (about 170 and 490 lines). Splitting them was declined
  for now: each reads as one table or one loop, and the tests hold them.
- **Tests.** I2 is not checked below the top level, no test runs over a real notes directory, and
  nothing tests CRLF or a 1 MB file ([04](04-org-core.md#testing)).

## The defects the plan began from

The first snapshot of the app, at `2cc66f7` (2026-09-20), listed these. They are cited as D1–D12 in
code comments and tests, so the numbers stay; every one is fixed. *Where* gives the place at that
commit.

| # | Issue | Where | What became of it |
|---|-------|-------|-------------------|
| D1 | Server caches files at startup and never re-reads disk | `Application.kt:81,112` | `FileStore` reads on demand and refuses a write whose baseline is stale |
| D2 | Whole corpus is parsed and shipped as JSON on every cold start | `Application.kt:169` | T9/T10: the endpoint is deleted, and the client syncs text through the manifest, fetching only stale files, in one bundle when eight or more are stale |
| D3 | No local persistence of documents on the client | `composeApp/…/data/` | T8: a local replica; the app opens and edits with no network |
| D4 | AST cannot round-trip to its own source text | `OrgMode.kt:329` | T3: every node carries its source range and the document its text, so the model is lossless; edits are range replacements (T7) |
| D5 | Two independent Org grammars | `OrgMode.kt` vs `OrgSyntaxHighlighting.kt:85` | T5/T6: `OrgSyntax` and `orgSpans()` in `shared/` are the one grammar, and `OrgElements.kt` uses them too. What crept back later was folded in again; what stays apart is in [04](04-org-core.md#one-grammar-two-consumers) |
| D6 | Full-document re-highlight per keystroke; whole document composed eagerly | `OrgSyntaxHighlighting.kt`, `OrgDocumentView.kt` | Highlighting by cache and per-element editing; reading mode is a `LazyColumn` keyed by element (T12b) |
| D7 | Calendar only understands `SCHEDULED` with a plain date | `FileTreeViewModel.kt:230` | T15–T17: a timestamp parser, an agenda engine (`AgendaIndex`) and agenda, day, week and month views |
| D8 | No authentication, no TLS, binds `0.0.0.0` | `Application.kt:36` | T2 added a token; pairing replaced it (T30): devices sync over TLS on 8443 with a pinned certificate and a token each, and plain 8080 answers this computer only |
| D9 | No conflict detection on save | `Application.kt` | T11: a diff3 merge, and a conflict copy when both sides changed the same lines; neither version is lost |
| D10 | No compression on server responses | `Application.kt` | T2: gzip on |
| D11 | Cannot create, rename or delete files from the app | `Application.kt:189` | T20: the drawer creates, renames and deletes notes, offline too, synced through tombstones ([05](05-sync-protocol.md#creating-renaming-and-deleting-t20)) |
| D12 | Flat document model (sections are not nested) | `OrgMode.kt:19,93` | T4: headings nest, and a heading's range covers its subtree |

---

## Phase 0 — The server

### T1 · Server reads the disk, not a boot snapshot
`FileStore` answers from disk on every request, hashing content for ETags with a `(size, mtime)` cache
so unchanged files are not re-read. The old server answered from a map built at startup, and could
overwrite an edit made in Emacs. The file watcher and the SSE stream came with T9.

### T2 · Transport hygiene
**Superseded by T30.** Pairing (1.0.0) replaced the token and both switches; gzip stays. The plain
port, 8080, listens on loopback and answers only a loopback `Host`: the web client, the desktop app
and the Devices page. Paired devices reach the server over TLS on 8443, trusting its self-signed
certificate by the fingerprint the QR code carries, each with a token of its own that the Devices
page can revoke. `KORGNOTE_TOKEN` is ignored, with a line in the log if it is set;
`KORGNOTE_ALLOW_LAN` and `KORGNOTE_REQUIRE_TOKEN` are gone. The shared token was one secret on every
device, sent over plain HTTP, and the LAN switch meant no authentication at all; pairing removed the
need for either.

---

## Phase 1 — Org core ([04](04-org-core.md))

### T3 · Source-mapped documents
`range` and `text` are body properties rather than constructor parameters, deliberately: these are
data classes, so a constructor property would join `equals`, and every expected node in every parser
test would have to spell out exact offsets. `@Serializable` went from the node types once the wire
carried raw text, which is what let T4–T7 reshape the model freely. Table *cells* keep an empty
range; locating them needs intra-line offsets, and nothing has needed them yet.

### T4 · Nested headings
`OrgSection` lost `content: String` and gained `children`, so a section owns its body *and* its
sub-headings, and its `range` covers the whole subtree: deleting or moving a heading with everything
under it is one text replacement. Planning lines became children of the heading they were written
under, so the agenda reads a task's dates off the task instead of inferring them from document order.
**I3 does not hold in its literal "every line belongs to exactly one leaf" form.** A heading line is
owned by its section, which stops being a leaf once it has children, and blank lines are owned by
nothing. Both would need nodes the grammar does not have. What is tested is the strongest form that
is true and the one a renderer needs: every non-blank line has exactly one *deepest* owner, and the
nodes claiming it form a single ancestor chain.
**Measured** Run over the real notes directory: 366 files, 0 round-trip failures, 0 range violations,
0 nesting violations, deepest tree 7 levels.

### T5 · Complete the grammar
`OrgSyntax` moved from `composeApp/` into `shared/`, so the parser and the highlighter recognise Org
from one set of patterns. Blocks and drawers are **opaque**: their contents are kept as text and never
parsed, because a `* heading` inside a source block is code. Unknown constructs fall through to
`OrgRaw`, never to an error.
**Found by the real corpus, not by the tests** The list grammar matches `*` as a bullet, so a plain
list swallowed every heading that followed it: 272 headings across the real notes, and 23 files where
the element splitter and the parser then disagreed about where a heading started, which is the
condition for an edit to splice over the wrong range. A hand-written corpus missed it because the two
constructs have to be adjacent to trigger it. `aListStopsAtTheNextHeading` and
`theSplitterAgreesWithTheParserOnTheNewConstructs` pin it down. After the fix, over the real notes: 366
files, 0 round-trip failures, 0 range violations, 0 nesting violations, 0 parser/splitter
disagreements.

### T6 · One grammar
`orgSpans` and `orgSpansForLine` in `shared/` say what a stretch of Org text *is*; `composeApp/` only
maps a kind to a colour. `OrgLineState` is public so a client keeping a per-line cache can thread the
state itself, which is what let the editor's incremental cache survive the change untouched.
**Reading mode still walks the line itself.** It does not just style text, it *rewrites* it, hiding
emphasis markers and link targets and turning a `LOCATION` property into a Google Maps link, and a
list of spans over the original text cannot express a text substitution. Spans could grow a "replace
this range with this text" facet; until then the reading renderer consumes the same shared patterns
but keeps its own walk. The *grammar* is unified; the second *renderer* is not.
**Since** Later features brought Org patterns back into `composeApp/` and `shared/…/edit`. The
refactor of 2026-11 folded them into `OrgSyntax` again, or into what they copied; what is left, and
why, is the table in [04](04-org-core.md#one-grammar-two-consumers).

### T7 · Structural edit API
`org.edit` has one primitive, `replace(range, replacement)`, which is invariant I4, and every
operation is built on it, so the rest of the file is untouched *by construction* rather than by care:
the bug that started this whole line of work, where saving rewrote blank lines nobody had edited, is
unrepresentable. The ordinal path went (`toggleNthCheckbox`, `orgCheckboxesBefore`,
`toggleCheckboxByOrdinal`), and with it the O(n²) checkbox counting the reading view did per element
(12 ms on the biggest real file). `cycleTodo`, `setTags`, `promote` and `demote` came with this task;
nothing called them once the long-press menu moved subtrees by element range, and they were deleted
(4fc206c).
**Measured** Across the real notes directory: 366 files, 127 checkboxes and 2397 headings, every toggle
changing exactly one line, every `setTodo` preserving the line count, and every demote/promote
round-tripping to the original bytes. The one apparent failure in that sweep was the script's own
fault: it located the demoted heading by title and level, which is ambiguous when two headings share a
title. A neat illustration of why ordinals and titles were the wrong handle.

---

## Phase 2 — Local-first ([05](05-sync-protocol.md))

### T8 · Replica store
`ReplicaStorage` has three implementations: one over `java.io` for the JVM and Android, one over
`NSFileManager` for iOS, and `InMemoryReplicaStorage` for the web and for tests. A failed load used to
clear the tree "to avoid showing stale content", which meant an unreachable server left the app empty;
now it costs freshness, not access. One `DocumentStoreContract` runs against both the file and the
memory storage, which is what makes the memory store a fair stand-in for the web build.

### T9 · Sync API v1 (server)
A stale `If-Match` answers **409**, not 412, and includes the current `ETag`: this is a mergeable
conflict, not a precondition the client should give up on. A deleted note moves to
`.korgnote-trash/<date>/` rather than being unlinked, which makes a sync bug recoverable.

The journal was meant as a pure optimisation, safe to hold only in memory, since a `since` it cannot
cover returns a full manifest. **Found later: it was not safe.** A restarted server counted from zero
again, so once it passed the generation a phone had kept, it answered that phone with only the later
changes, and the ones in between were never seen. The journal now starts at the time it is made, in
milliseconds, so a generation from an earlier run always asks for a full manifest. A delta lists each
path once, as its latest change, and is read under the same lock as its generation (4d44808). The
client keeps a full manifest's generation as given, not only when it is higher (094fb3f).
**Measured** An external edit was detected in **110 ms**, against the "within seconds" the protocol
promises. Every criterion was also checked with `curl` against a real server. The SSE stream itself has
no automated test: driving a never-ending response through the Ktor test host deadlocked the test, so
it is covered by curl and by the journal and watcher tests underneath it, rather than by a flaky test
pretending to be evidence.

### T10 · Sync engine (client) and cutover
`/api/org-content`, `/api/org-tree-paths` and `/api/content/{…}` went along with `KtorOrgRepository`
and `OrgRepository`. Nothing in the client parses a server-provided AST; it moves text and etags only
(D2, D3). Every branch of the table was written so that no edit is ever discarded, including the
awkward row where a file was deleted on the server but edited here, which re-pushes it.

### T10b · Web client on the same engine
The wasm target runs on the shared stack: `InMemoryReplicaStorage`, settings in `localStorage` (pins,
new-notes folder, file-link folders), the same `SyncEngine` and view model as the native clients, and
a same-origin base URL from `window.location.origin`, so no host or port is configured on web. Since
pairing (T30) the web client is served on the plain port only, which answers this computer alone, and
a browser cannot pair. What is left is under [open gaps](#open-and-known-gaps).

### T11 · Conflicts
`shared/…/sync/Merge.kt` is a line-based three-way merge (diff3), dependency-free, with the timestamp
injected so it can be tested without a clock.
**One deliberate departure from diff3.** When both sides only *added* lines at the same point, both
additions are kept rather than declared a conflict. Strict diff3 conflicts there, rightly for source
code where order can matter. For a list of Org headings it does not, and it is the commonest case
there is: capturing a TODO on the phone and another on the laptop. Keeping both loses nothing;
conflicting would litter the notes with copies for the most ordinary thing a person does.
**Never** are conflict markers written into a note. A file full of `<<<<<<<` is a corrupted note, and
the merge has no code path that can produce one.
**Reversed since** (daf827b, 2026-10-25). When a conflict cannot be merged, the **server's** version
keeps the path and this device's is saved beside it as the conflict copy
(`DocumentStore.keepServerVersion`). The server is the source of truth: with the local version at the
path, the next cycle pushed it over the file Emacs had open, a version nobody merged.
**Found while testing** The fake server in `SyncEngineTests` derived etags from content *length*, so
two different edits of equal length looked identical to it and the conflict path was never reached.
Etags now follow content. A fake that is too forgiving hides exactly the case the tests exist for.

---

## Phase 3 — Editor ([06](06-editor.md))

### T12a · Editor performance diagnostic
Cost A, highlighting; the numbers are in [notes/editor-perf.md](notes/editor-perf.md).

### T12b · Editor surface
**Dropped as impossible:** the `TextFieldState` migration, since that API has no styling hook
([06](06-editor.md#the-state-based-api-cannot-be-used)). **Dropped as unnecessary:** block
virtualisation, since T12a came back as Cost A and per-element editing removed document size as a
concern. **Closed by T22:** read↔edit scroll mapping and a debounced, cancellable re-parse existed only
for the whole-file editor, and went with it, as did viewport-scoped styling. The per-line cache and
metric-neutral styling remain, for the element editor. Lazy reading mode took first composition of the
largest real file from 234 ms to 38 ms ([09](09-element-editing.md#what-reading-mode-costs)).

### T13 · Completion
**A strip, not a popup.** The design sketched a floating popup for desktop and a strip for phones.
Both get the strip: the popup fights the IME and the selection handles, phones are where these notes
are mostly written, and one presentation is one thing to keep working. Tab accepts rather than Enter,
because Enter is a newline in a multi-line element, and stealing it would make an ordinary edit depend
on whether a chip happened to be showing.

**Pinned to the screen, after a first attempt that was not.** See
[06](06-editor.md#where-the-strip-goes-and-why-it-matters).
**Measured** Walking all 366 real notes for the tag counts costs 3 ms warm, 14 ms cold, so nothing
cleverer is warranted than a context rebuilt when the replica or the open file changes.
**Found while writing it** The editor spliced items with `insert.length` instead of the item's
`caretOffset`, which would have left the caret after `#+END_SRC` instead of inside the block. That is
why the splice lives in the engine as `applyTo`, where a test can reach it.

### T14 · Search
A linear scan, no index, no new dependency; [06](06-editor.md#search-g2) says what it does.
**The speed criterion is measured, not asserted.** A `measureTime` assertion went in first and came
back out: a clock in a test suite asserts about the machine running it, so it fails on a loaded laptop
while the code is unchanged. The corpus-sized test prints its time and checks only the result; an
accidentally quadratic scan would announce itself by never finishing, which is not a failure anyone can
mistake for a slow machine.
**Measured on the real notes** 366 files, 1.21 MB: a word takes 17–54 ms, a tag query 8–15 ms, and a
tag narrows a word search as it should (`cuisine` 66 hits, `tag:house cuisine` 61). A synthetic 4 MB
corpus scanned in ~200 ms, before the raw-text check T19 added. Which is the whole argument for the
linear scan: the index this would have needed is a thing to keep correct on every save, and it would
save tens of milliseconds.
**Found while testing** Search results and the agenda index went stale after a local save: both are
derived from the in-memory documents, and only the agenda one was being rebuilt. They are refreshed
together now, from one place, since forgetting one of them is invisible until someone reads results
describing a document as it used to be.
**Since** The drawer's filter parsed the same query at first, tags included. It looks at file names
alone now (362bc20); finding what is inside the notes is what search is for.

---

## Phase 4 — Agenda ([07](07-calendar-agenda.md))

### T15 · Timestamp parser
Stamps are found with `OrgSyntax.timestamp`, the pattern the highlighter already colours, so the agenda
never counts something the editor does not show as a stamp; the match is then parsed strictly.
**Malformed input** A stamp whose date does not exist or whose brackets do not pair is not a stamp. An
unrecognised token inside one, a French day name or `25:00`, is skipped and the date kept. A backwards
`<b>--<a>` is two stamps, not a range.

### T16 · Agenda engine
Entries are read from raw text (like search), element by element, skipping blocks and drawers. Choices
the design left open:

- Deadline warnings run from **today**, not from before it, and only for the **next** repeat, or a
  daily deadline would fill each day with a fortnight of copies of itself.
- An overdue deadline is carried to today like a scheduled item, with a negative `daysUntilDeadline`.
- An unfinished repeating scheduled item is carried once, from the date it started at.
- A heading's stamp is shown as the date, so it is stripped from the entry's title.

`AgendaIndex` holds the entries and expands them a month at a time as a view asks, since a daily
repeater has an occurrence on every month nobody looks at.
**Measured** The real notes (366 files, 1.2 MB): 1,874 entries indexed in 89 ms on the JVM; two months
expanded in 7 ms.
**Found on the real notes** Five files use `EVENT`/`FINISHED` without a `#+TODO:` line, relying on the
Emacs-wide `org-todo-keywords`, so their finished meetings read as undone and were carried to today.
The corpus's keywords were the answer until 2026-11-01; since then a file's keywords are its own
`#+TODO:` lines or Org's `TODO | DONE`, as in Org, so those files need a `#+TODO:` line of their own.

### T17 · Day and week views
**Decisions** A deadline's countdown shows on today only: the engine gives one for every day of the
warning period, and the agenda list would otherwise repeat it daily, as org-agenda's week view does
not. Multi-day ranges go in the all-day strip even when they have times, as calendars draw a trip.
Blocks are drawn at least 30 minutes tall so a stand-up has room for its title. The grid opens an hour
before now, but never below the first timed entry on screen. A drag horizon and a configurable day
start are not done: the grid scrolls, and nothing yet needed a setting.
**First try on a device: empty.** The app shell scrolled every screen but reading mode and search, and
inside a scroll the calendar's fill-the-rest content area measured zero height; the screen's own tests
gave it a fixed size and could not see it. The calendar owns its scroll like search, and
`AppCalendarTest` renders the real shell and opens the tab.

### T18 · Notifications
Only what is still ahead, within 14 days, soonest first, at most 60, since iOS keeps 64 pending and
drops the rest. The plan is handed over only when it changed, so a sync that changes nothing leaves the
device's notifications alone, and nothing is planned before the replica has been read, which would
otherwise cancel everything at start-up.
**Verified** On an Android 14 emulator holding the real replica, with the app process killed and Wi-Fi
and data off: the digest posted at 21:33:02 for 21:33; tapping it opens the app; a reminder's tap opens
its note at the heading.
**Inexact alarms were tried first and dropped.** The design preferred them. Measured on the emulator,
Android allowed a reminder three days out to arrive up to an hour late (it delays inexact alarms by up
to 75% of the time remaining, capped at an hour), and a test digest came four minutes late. Exact alarms
need `USE_EXACT_ALARM`, which Play reserves for calendar and alarm apps, and an agenda is one; inexact
remains the fallback if exact alarms are refused.
**Since** (9902878) neither platform asks on its own. "Not now" on the welcome screens was followed by
the question anyway, on iOS whenever the scheduler planned and on Android at every start. Now the
welcome screens ask, and a scheduler only checks (`NoteAgenda.onNotificationsAllowed`, e9f1ee0;
[07](07-calendar-agenda.md#notifications)).

---

## Phase 5 — What using it exposed

Nothing in this phase was foreseen when the plan was written: it is what a day of real use and three
regressions exposed, and then (T25–T29) what further use asked for.

### T19 · Continuous integration
`.github/workflows/ci.yml`: every push to master and every pull request runs the JVM and wasm tests of
`shared/` and `composeApp/`, the server's, and the wasm and Android builds, which are the compile check
for those two.
**iOS runs nightly, and only when master changed.** The repository was private then, so macOS minutes
counted 10× against the free quota: a nightly run of about 10 minutes would use 3,000 of the 2,000 a
month. It is public now and that reason no longer holds; iOS is still nightly. The check asks whether
master moved since the last scheduled run that passed, by comparing that run's SHA, rather than whether
it had commits in the last 25 hours, which a run starting later than the one before could skip a commit
through; a failed night is tried again. Nights start at 01:37 UTC, because runs scheduled on the hour
started hours late. A third job, `sweep`, plays 10,000 seeded sessions of `SyncEngineRandomTests` (T38,
d614601) on the same nights, while every push plays 30 inside the JVM tests.
**The first red run was a real one.** `OrgSearchTests.findsOneMatchInACorpusSizedCollection` passed its
assertions on wasm but took 3.4 s in headless Chrome, past Mocha's 2 s limit. Raising the timeout was
rejected as hiding the problem. The search itself was slow outside the JVM (2.7 s on the iOS simulator),
because it parsed every file before looking for the words. `searchOrgFile` now skips any file that does
not contain every term, which took the corpus test from 189 ms → 15 ms on the JVM, 402 → 95 ms on wasm,
and 2.7 s → 363 ms on iOS. A query term present in nearly every file still parses nearly every file.

### T20 · Creating, renaming and deleting notes
How the hard parts were answered is in
[05](05-sync-protocol.md#creating-renaming-and-deleting-t20). A delete warns when the note holds edits
the server has not seen. An empty folder does not exist in this model: one appears when a note is moved
into it. Templates came later (0970366, `template/NoteTemplates.kt`): a new note starts as org-roam
starts one, with its own `:ID:`, title, author, date and the tags typed in the dialog, then the header
lines Settings holds; a *Today* button opens today's daily, creating it in the daily folder first.

### T21 · Following links between notes
[09](09-element-editing.md#links-between-notes) has the rules. Completion agrees with them: it used to
write `[[file:notes/x.org]]` from the notes' root, which Org reads from the linking note's folder and so
broke in every subfolder. A test completes every note and resolves it. There is no `org-roam` database
or index: the corpus is small enough to scan, and an index is a thing to keep correct on every save (G7).
**Measured** The notes are org-roam's: 344 files carry an `:ID:`, and 104 links use one, more than any
other note link. Against them: 103/104 `id:` links, every `file:` link to a note inside them, and 111
fuzzy links resolve; the misses are links that are broken in Emacs too. One backlink pass over all 367
notes takes ~33 ms on desktop, and is done once per change to the notes, not per note opened.
**Not included** Following links from inside the editor. The system back gesture came later (5310dcd):
Back closes an open drawer first, and otherwise returns from a followed link, as the top bar's arrow
does.

### T22 · Whole-file editing, reconsidered
**Retired.** Per-element editing was already the path every edit took; the whole-file editor was kept
only for restructuring, and that is in the long-press menu now. Two editing surfaces meant every editor
decision was taken twice or forgotten once, and the second one was where the untested code lived. The
preamble needed nothing new: it is element 0, and a tap edits it like any other. Removed: the top bar's
Edit/Save buttons, the whole-file `BasicTextField`, `EditorViewport`, viewport-scoped styling with
`OrgViewportHighlightTest`, and `OpenNote.onContentChanged`. Refile, which this task left out, came in
1.6.0 ([09](09-element-editing.md#refile)); keyboard shortcuts for any of it did not.

### T23 · Server container
A multi-stage [`Dockerfile`](../Dockerfile) (`:server:installDist` on a JDK 25 image, then
`gcr.io/distroless/java25-debian13:nonroot`, the same JRE with no shell or package manager, so far fewer
packages for CVEs to land in, e4ebc1e) and a [`docker-compose.yml`](../docker-compose.yml). The image is
published to GHCR for amd64 and arm64 on each `v*` tag by `container.yml` (e094b3e).
**A missing or unwritable `/notes` is fatal** (`KORGNOTE_REQUIRE_NOTES_DIR`), since in a container an
empty library would hide a typo in the volume path. Writability is checked by writing a probe file,
because through Docker Desktop's file sharing a `chmod 555` directory reports `canWrite()` as true and
then refuses the write. The container runs as a non-root user, and compose sets it to
`KORGNOTE_UID:KORGNOTE_GID`. `UID` itself cannot be used because it is read-only in bash and zsh.
**Build context** Not a problem: with `local.properties` excluded and no SDK in the image, Gradle
configures the Android modules and builds `:server` without complaint. The build stage needs
`libatomic1`, which the Node that the Kotlin plugin downloads links against.
**Verified** On Docker Desktop for macOS: a host edit arrived as an SSE `changed` event within seconds
through the bind mount; a `PUT` from a client created the file on the host owned by the host UID; each
refusal (no mount, `:ro` mount, unwritable directory) exits with a message. Not yet run on a Linux host.

### T24 · Tap to edit, fold instead of pencil and +
[09](09-element-editing.md#the-surface) describes it. Why: with a few hundred elements in a file, a
pencil and a **+** on each were a few hundred pairs of identical icons, taking width from the text and
making the note look like a form.
**One deviation** Folds are keyed by heading line plus occurrence rather than tree position, which also
moves when a sibling is added above; see [09](09-element-editing.md#folding).
**Since** The editor's floating Discard, save-and-add and Save buttons moved onto the editing strip,
since they covered the last element of a file (149b5f9). Save-and-add then left the strip (dcec372): the
long-press menu and the button at the foot of the note still add a heading. The strip was fitted to a
phone once it was used on one (ace6cc7).

### T25 · Pinned notes
**Why** A few notes — an agenda, a todo list — are opened far more than the rest, and finding them
in a tree of hundreds is a scroll every time. Which notes they are is the reader's, so nothing is
named in the code.
**Landed** A note's long-press menu in the drawer offers *Pin* (or *Unpin*). Pinned notes are listed
above the filter, in the order they were pinned, by file name — or by path where two share a name;
a tap opens one, a long-press unpins it. With nothing pinned the drawer is as it was. At launch the
first pinned note opens instead of the alphabetically first one; since T42, the note last read comes
before it.
**Where pins live** In settings, per device, as paths: pinning changes nothing in the notes and
syncs nothing, and a phone and a laptop can keep different ones. A rename or move in the app carries
the pin, in its place; a delete drops it. A pin whose note is missing — renamed on another device,
or not yet synced — is hidden rather than dropped, and returns with the note.
**Verified** `PinnedNotesTest` (view model: saving, order, launch, rename, delete, missing notes,
labels) and two cases in `NoteLifecycleUiTest` (the menu item, the pinned rows).
**Not included** Reordering pins, and pinning a heading rather than a note.

### T26 · Linked files
**Why** The notes link to files kept beside them in a synced folder — `[[file:~/Sync/invoice.pdf]]`
— which Emacs opens in the system's viewer. Reading mode drew them as dead text. The folder is
synced to the phone too, but somewhere else, so the path as written means nothing there.
**Landed** In reading mode a `file:` link to anything but a note, or a bare `~/…` or `/…` path to
one, is tappable and opens in the app the device picks for the file; its `::search` is dropped.
Settings has a *File links* section: each row pairs a folder as the links write it (`~/Sync`) with
where that folder is on this device. The longest covering folder wins, matched folder by folder.
**Per platform** Android cannot read shared storage by path, so there the folder is chosen in the
system's folder picker, which grants read access to it for good; the file is found under it by
name and handed to the viewer as a content URI. An unmapped path says which setting to add.
Desktop takes a typed path, and opens an unmapped one as written, `~` being the home here too.
iOS and the web say that files cannot be opened from links. Mappings are per device, in settings.
**Verified** `FileLinksTest` (mapping, longest match, whole folders, messages, storage, settings
validation), the link annotations in `OrgSyntaxHighlightingTest`, `fileLinkPath` in `OrgLinksTest`.
The Android picker and viewer hand-off have no automated test.
**Not included** Links relative to the note (`./photo.jpeg`), which name files beside the notes
where only notes are synced; `.org` files outside the notes; iOS.

### T27 · Highlighting src blocks
**Why** Code blocks are a large part of the notes — mostly shell and Emacs Lisp, then Java — and
were drawn as one grey monospace run.
**Landed** `OrgSrcLexer.kt` in `shared/` lexes the body of a `#+BEGIN_SRC` block by the language it
names into `Code*` span kinds: keywords, strings, comments, numbers, constants, the command of a
shell line, the name a Lisp definition introduces, config keys, markup tags. Four families of hand-
written lexers — shell (and Dockerfile), Lisp (Doom's macros included), C-like (Java, Kotlin, C,
C++, C#, JavaScript/TypeScript, Go, Rust, Zig, Lua, Python, SQL, JSON), configuration (INI/conf,
TOML, YAML) — plus XML/HTML. Unknown languages stay plain monospace. Editing gets the spans through
`orgSpansForLine`; reading mode's `OrgBlockView` through `srcBlockSpans`, so both agree.
**Why no library** It has to run on wasm and iOS, one line at a time for the editor's per-line
cache, and the multiplatform highlighters have no Lisp. A string, comment or heredoc left open at the
end of a line is carried in `OrgLineState.codeState`, so the cache's "same incoming state" check
still holds. Token spans absorb the whitespace beside them, as heading pieces do, so no style run
holds only whitespace (`OrgWhitespaceRunTest`).
**Verified** `OrgSrcLexerTests` (each family, multi-line state, state with and without classifying,
whitespace runs, bounds) on JVM, wasm and the iOS simulator; two cases in `OrgWhitespaceRunTest`.
Run once over the 371 real notes: no out-of-range span.
**Not included** Lisp function calls (as in Emacs, only special forms, macros and defined names are
coloured — a call cannot be told from a `let` binding without more than one line of context),
variables inside unquoted heredocs, YAML block scalars, nested languages (`<script>` in HTML).
Colours were for the light theme, then the only one; since the dark theme (T36) src blocks take
GitHub's dark colours there.

### T28 · Links without syntax
**Why** On a phone keyboard `[[target][label]]` is the hardest thing in the notes to type, and the
only ways in were the note finder and typing it. Changing a link's label meant editing inside its
brackets; a file link meant typing a path the phone does not even use.
**Landed** `LinkEditing.kt` in `shared/`. A web address pasted while editing becomes `[[address]]`,
or `[[address][words]]` when pasted over words; it stays bare where Org wants it bare — a property
such as `:ROAM_REFS:`, a `#+` line, a block, a `[[` being typed. A paste is told from typing by
the address arriving whole in place of the selection. With the caret on a link, the strip's link
button becomes *Edit link* — kept in front of the suggestions that the caret in a target brings up
— and opens a form: the label, then *Web* (an address; `example.com` gets `https://`), *Note* (the
note finder, linking by `:ID:` as before) or *File*. *Remove link* leaves the label as text. The
finder's *Web or file link…* opens the same form for a new link, the selection as its label.
**Files, in reverse** *Choose a file…* opens the system's picker — on Android in the first folder
under File links — and writes the path as the notes' own machine knows it: a file under the folder
set for `~/Sync` is `~/Sync/…`; the closest folder wins, as the longest does the other way. Android
reads the path from shared storage's document ids, or from `findDocumentPath` for other providers,
and refuses a file outside every linked folder (Recent, Downloads) with what to do instead.
Desktop uses its file dialog; outside a mapping it writes the path, home as `~`. iOS and the web
type the path.
**Verified** `LinkEditingTest` (16), `LinkEditTest` (7), reverse cases in `FileLinksTest`,
`DesktopLinkPathTest`, `AppLinkEditTest` (the form through the real screen). On the phone: a paste
from the text menu, the form on note and web links, a PDF chosen in `Sync/board_game` written as
`~/Sync/board_game/…`, every edit discarded and the replica compared byte for byte afterwards.
**Not included** Fetching a page's title for the label (it would mean a request to the site from
the phone); editing a link from reading mode without opening the element; iOS's picker.

### T29 · Saved searches
**Why** Asked for by a prospective user. The same few searches — `tag:work TODO`, a project's name
— get typed again and again; what is worth keeping is the query, not its results, which are
whatever the notes hold when it is run.
**Landed** With something typed, the search field shows a bookmark: a tap names the search (the
query itself by default) and saves it; on a saved query the bookmark is filled and a tap forgets
it. A button at the start of the search row opens the app's drawer, which on the Search tab lists
the saved searches instead of the files, by name with the query beneath. A tap runs one at once —
no typing pause to wait out — and closes the drawer; a long-press renames or removes it.
**Where they live** In settings, per device, as JSON, the same way the pins do: nothing in the notes
changes and nothing syncs. A search is known by its query, so saving a query already saved renames
it rather than listing it twice.
**Verified** `SavedSearchesTest` (6: order, persistence, rename by re-saving, blank names, remove,
running at once, encoding) and `SavedSearchesUiTest` (2: saving and running through the real shell;
forgetting from the bookmark).
**Not included** Reordering, syncing them between devices (an Org file of queries would do that and
is worth considering if they are wanted on every device), and the structured query grammar.

---

## Phase 6 — Shipped without a plan

Written after the fact, from the commits, so that every task number is on record. Each commit message
says why; these say what.

### T30 · Pairing by QR code, and TLS (1.0.0)
**Landed** The server has two ports. 8080, plain, answers this computer only: the web client, the
desktop app, and a Devices page that lists paired devices, revokes them and shows a pairing QR code
(`scripts/pair.sh` prints it in a terminal). 8443 serves the sync API over TLS, with a self-signed
certificate made on first start, to paired devices only. The code carries the server's id and
addresses, the certificate's SHA-256 and a one-time code a device trades for a token of its own; the
server keeps only a hash. The app trusts the server by that fingerprint and nothing else, and tries
its addresses in turn (bfe1c27). Each pairing builds a client that trusts only the certificate its
link names — the iOS simulator showed a wrong fingerprint still pairing over the app's open
connection — and `korgnote://pair` links open the app (0e8704f). Phones have no plain-HTTP server
fields, and Android refuses cleartext traffic (65ee042). A revoked device is told to pair again
rather than that the server cannot be reached (beb8f9b).

### T31 · Notes as a folder on the phone (1.2.0)
**Landed** A phone can keep its notes in a folder of the user's instead of syncing with the server:
one Syncthing, Dropbox or iCloud Drive keeps in step, or nothing does. Nothing of KorgNote's is
written into it. `FolderNoteStorage` reads it through a `NoteFolder` per platform (the Storage Access
Framework on Android, a bookmarked URL with coordinated access on iOS) and looks again when the app
comes to the front and every 10 s while it is there. A save over an edit made elsewhere goes to a
`.sync-conflict-` copy. Welcome screens on a first start set the choices side by side (1103226).
Reads go eight at a time, which took a first load of 400 notes over SAF from 44 s to about 10 s;
pairing links open from a folder setup too (007f952). After a conflict the note shows the folder's
version at once, and the banner names the folder rather than the server (3373483).

### T32 · WebDAV source, experimental (1.3.0)
**Landed** A WebDAV server, such as Nextcloud, as a third place for the notes on Android and iOS. It
reuses the sync engine unchanged through `WebDavSyncApi`, with a replica, an engine and an HTTP
client of its own. Before a server is used the app checks that it refuses a write or delete over a
version it no longer holds and a creation over an existing file; one that does not, such as
`rclone serve webdav`, is refused with why. Real servers needed three workarounds: Nextcloud's 404
for a missing folder, Apache's weak etag in the second after a write, and Nextcloud keeping an etag
across a same-size write in the same second (4700a45). Requests say `Accept-Encoding: identity`,
since a gzipped answer got an etag no `If-Match` matched and no Android push went through (56ce835).
A 401 says which way to sign in the server asked for (215278e). `WebDavServersTest` syncs two
devices through Apache, rclone and Nextcloud in Docker.
**Not included** Any sign-in but Basic.

### T33 · Android home-screen widgets (1.3.0)
**Landed** Two Glance widgets, the agenda and the month, drawn from the agenda's entries, with a
sync button that runs a one-off `WorkManager` job (8c298f0). The app's strings are packed into the
APK, without which it closed on start; the agenda widget's TODO keyword moved onto its status line,
and the picker previews use only views a widget may hold (7952a77). [07](07-calendar-agenda.md#home-screen-widgets-android)
describes them.
**Not included** Ticking off a TODO from a widget, and iOS.

### T34 · Grid table editor and formulas (1.0.0)
**Landed** A tap on a table opens it as a grid, a field per cell, with menus to insert, move and
remove rows and columns; Save writes it back aligned (fecf931). `#+TBLFM:` formulas are read,
recalculated with Calc's arithmetic and printing, and renumbered when rows and columns move, with a
review of what cannot be computed or looks like a slip (ee6342c). The grid edits formulas and
recalculates on demand (210991e). Columns align by what their cells show, as Emacs aligns them
(f186e92). More of Calc's functions, dates included (8ba8bd6). A Table button makes a new one in the
grid (a12b071). Back asks before throwing a changed table away (86a403e).
**Not included** Trigonometry: Calc answers it in degrees with exact special cases.

### T35 · Translation (1.4.0)
**Landed** The app's text moved into Compose resources, one strings file per area, with a French set
beside the English (82a7bf3); months and weekdays are named in the app's language (29cb50a). The
widgets take theirs from Android's resources, and Android 13 and later offers the language per app
(f9b1236).
**Not included** Completion's labels: they are made in `shared/`, which has no resources, so `today`,
`tomorrow` and the weekday names it offers are English only.

### T36 · Dark theme (1.0.0)
**Landed** The app follows the system's dark setting. The Org palette has a dark value for each
colour and src blocks take GitHub's dark theme; on Android the window theme follows too, so the app
no longer flashes white as it opens in the dark (b42d920).

### T37 · Release pipeline
**Landed** `scripts/release.sh` signs release builds with the key kept in KeePassXC, and debug builds
became an app of their own (a152c33). `scripts/publish.sh` raises the version, runs the tests, builds
and checks the signed APK, tags, pushes and makes the GitHub release (68f35bc). A version tag builds
the server image for amd64 and arm64 and pushes it to GHCR, and the release waits for it
(e094b3e). `fastlane/` holds the store listing, and the APK leaves out Google's dependency blob
(4d9d456). The README offers Obtainium for updates; F-Droid and IzzyOnDroid were dropped
(fd5c437). KorgNote is licensed under the GPL (9dccb97).

### T38 · Randomized two-device sync tests
**Landed** `SyncEngineRandomTests`: two devices doing seeded random edits, creates, deletes, renames
and conflict resolutions to a few shared names over a network that drops requests before and after
the server acts. Then everything syncs until quiet, and the replicas must match the server, no
line anyone wrote may be missing unless a device removed it, and no note may hold two notes'
headers. A failure names its seed and its steps (6e76835). Every push plays 30 sessions; a nightly
job plays 10,000, on the JVM only, since the engine is common code (d614601).

### T39 · Secrets sealed, notes kept out of backups
**Landed** The pairing token and the WebDAV password are sealed with an Android Keystore key, which
no backup holds, and kept in the Keychain on iOS; a value saved in the clear is sealed the first time
it is read (9072fd5). The app's copies of the notes, the widgets' agenda and the reminder plan are
left out of Android's backups and device transfer, and out of iCloud and computer backups on iOS;
the next sync rebuilds them (c5ec213).
**Not included** The desktop, whose pairing stayed in plain preferences until it was sealed with a key in the system's keyring (`KeyringCipher.kt`).

### T40 · Sync robustness
**Landed** A run of fixes, most found on a phone or by T38:
- A first sync of 371 notes took about 80 s file by file; a cycle that pulls eight or more files now
  asks for the bundle, about 15 s. Cycles run one at a time, which stopped a conflict copy's DELETE
  going out three times (eb46572).
- An edit being made is kept when a sync brings a newer note: saving makes the edit on that note,
  merged line by line, and reloads parse only what changed, off the main thread (5b9e083).
- A note created at the same path on two devices, or over a name deleted or renamed away here, is
  never merged against a base it does not have: the server's keeps the path and this one becomes a
  conflict copy (4ff6d10, 4a4a650).
- A change stream that died without a word is noticed: the server sends a heartbeat every 30 s, and
  90 s of silence counts as a dead connection, followed by a catch-up sync (27e1dfc).
- The app syncs on coming back to the foreground (50b7173).
- Generations survive a server restart, a delta lists each path once and is read atomically, and
  the client keeps a full manifest's generation as given (4d44808, 094fb3f; see T9).
- A replica whose `meta.json` is lost keeps any note that differs from the server's as a conflict
  copy instead of overwriting it (094fb3f).
- A new note cannot be created through a symlink that leads out of the notes directory, as reading
  and editing one already could not (4d44808).

### T41 · Clocking (1.6.0)
**Landed** Org's clocking, written byte for byte as Emacs writes it: *Clock in*, *Clock out* and
*Cancel clock* in a heading's long-press menu, clocking in stopping any other clock; totals beside
headings, against `:Effort:`; a running-clock bar on every tab, with a warning and *Clock out at…*
for a clock left on; `=> H:MM` summed again when a clock is edited by hand; `CLOCK:` lines
highlighted; on Android an ongoing notification whose *Clock out* works with the app closed. The
design is [11](11-clocking.md). Covered by `ClockingTest` (shared and view model), `AppClockingTest`,
`ClockEndTest` and `OrgSpansTests`.
**Phase 2** Clocked time drawn in the day and week beside what was planned, a total above them
that opens its report, the agenda's clock report under its days, and *Clock in* or *Clock out* from
a long-press on a calendar entry. Covered by `ClockedSpansTest`, `CalendarClockingTest` and
`CalendarLayoutTest`.
**Since** Changing a heading's keyword from the calendar's long-press, clocking back into a recent
task from the bar, and *Set effort…* in the heading's menu.
**Seen on a device** 2026-11-01, on a Galaxy A52s (Android 14) against a scratch server: clock in
from the menu, written to the server at once; the notification with its chronometer, which outlived
the app's process; its *Clock out* with the process killed, which wrote the stop, synced it and took
the notification down; a clock started on the server picked up within seconds, bar and
notification; switching to a recent task; the day view's blocks and total, the agenda's report;
a keyword set from the calendar; an effort set from the menu.

### T42 · Resume where the app was left (1.6.2)
**Landed** [09](09-element-editing.md#resuming) says what it does.
**How** `ReadingPlaces` keeps, per device, the 50 notes last read, each by the offset of the element
at the top of the screen and the pixels scrolled into it. The reading view reports every frame;
the view model stores the place once scrolling stops for half a second, before another note
replaces it, and when the app pauses. `OpenNote.settled` keeps a position from being taken for the
new note while the view still shows the previous one. The tab and calendar view are `LastScreen`,
kept by the shell. Folds are still session only.
Covered by `ReadingPlacesTest` and `AppResumeTest`.
