# 06 — Editor: scale, completion, search

Addresses G2, G3 and defect D6. Depends on the source-mapped model from
[04-org-core.md](04-org-core.md).

> **Status.** The performance work is done and the findings below are measurements, not plans.
> Per-element editing ([09](09-element-editing.md)) has since become the only editing surface and
> removed document size as a concern: the whole-file editor this document was written for was
> retired by T22, and with it viewport highlighting. The metrics constraint and the per-line cache
> still apply to the element editor. Search (T14) and completion (T13) are both built.

## Where it hurt

| Symptom | Cause | Status |
|---|---|---|
| Typing cost grows with document size | `OrgSyntaxHighlightTransformation.filter` re-highlighted the **entire** document on every keystroke | Fixed — per-line cache + viewport scoping; since T22, edited text is one element |
| Redundant work per keystroke | `filter` ran 3× per keystroke, all on identical text | Fixed — memoised |
| Opening a large file composes everything at once | `OrgText` rendered into a plain `Column` inside an outer `verticalScroll` | Fixed — a `LazyColumn` of elements; see [Reading mode at scale](#reading-mode-at-scale) |
| Programmatic edits are awkward | The `String`-valued field gave no cursor control | Fixed for the element editor — it uses `TextFieldValue` |
| Structural actions are ordinal-based | Checkbox toggling addressed the *N*th checkbox instead of a node | Fixed by T7 — a node is identified by its source offset |

## Performance: where the time actually goes

Documents over ~40 KB were noticeably slow to type in, worst on iOS and wasm. Two independent costs
were in play, needing completely different fixes, so the rule was **measure which one dominates
before choosing**. Measured results are in
[notes/editor-perf.md](notes/editor-perf.md); the answer was Cost A.

### Cost A — highlighting the whole document on every keystroke

`OrgSyntaxHighlightTransformation.filter` runs `buildOrgHighlightedString` over the entire
text each time the field recomposes (`OrgSyntaxHighlighting.kt:481`). For a 40 KB file
(~1,300 lines) that is on the order of 1,300 line splits × ~20 regexes ≈ tens of thousands
of regex executions, producing an `AnnotatedString` with thousands of style spans — all
synchronously, on the main thread, because the text field needs the result to lay out.
Rough order of magnitude: tens to hundreds of milliseconds per keystroke on wasm.

There is also a smaller, related cost: the `String`-valued `OutlinedTextField` sends the
whole document back through `onValueChange` → `mutableStateOf` on every keystroke
(`App.kt`, `FileTreeViewModel.kt:83`), copying 40 KB and invalidating the whole field.

### Cost B — text layout of the whole document

Compose text fields do not virtualise. A `BasicTextField`/`OutlinedTextField` holding 40 KB
measures and lays out *every* line, including the ~95 % that are off screen. Skia-backed
targets (iOS, wasm) pay the most for text shaping, which matches the platforms where you
feel it first.

Cost B is architectural: no amount of tuning the highlighter removes it.

### The diagnostic (do this first)

Temporarily pass `visualTransformation = VisualTransformation.None` and type in the same
40 KB file on iOS and wasm.

| Result | Conclusion | Fix |
|---|---|---|
| Typing becomes fast | Cost A dominates | Viewport highlighting + `TextFieldState` — no architectural change ([below](#editor-surface)) |
| Still slow | Cost B dominates | Editor virtualisation is required ([below](#if-layout-is-the-wall-block-virtualisation)) |

This is a one-line experiment that decides whether T12b is a day or a fortnight. Record the
answer in `docs/notes/editor-perf.md`.

### Do coroutines help?

Not with throughput, and it is worth being exact about why:

- **Kotlin/Wasm has no threads.** `Dispatchers.Default` resolves to the same single event
  loop as the main dispatcher, so moving parsing or highlighting into a coroutine there
  changes *when* the work runs, not how long it takes or who is blocked by it.
- **Highlighting cannot be made asynchronous anyway** while it goes through
  `VisualTransformation`: the field needs styled text during layout. You can only make it
  *smaller* (viewport scope), not *later*.

What coroutines genuinely buy, on every target:

1. **Debounce + cancel.** Re-parsing on each keystroke while typing at 10 cps queues ten
   jobs, nine of which are already obsolete. A `MutableStateFlow` of the text, plus
   `debounce(50)` and `collectLatest`, collapses that to one run per typing pause and
   cancels superseded work. This is usually the single biggest *perceived* win and costs a
   few lines.
2. **Chunk + `yield()`.** For work that is genuinely long — the initial parse of the whole
   corpus, search, agenda expansion — process in chunks with `yield()` between them. On
   wasm this lets frames render during the work, turning a 400 ms freeze into a progressive
   update. Same total time, very different feel.
3. **Real parallelism where it exists.** On JVM, Android and iOS (Kotlin/Native's current
   memory model supports multithreaded coroutines), `Dispatchers.Default` is a real thread
   pool, so corpus-wide work — indexing, search, agenda — does run off the main thread.
   Write that code coroutine-shaped with `yield()` and it degrades gracefully to
   cooperative interleaving on wasm instead of breaking.

So: coroutines are the right tool for *scheduling* the work, and irrelevant to the cost of
the work. The wins below come from doing less.

### If layout is the wall: block virtualisation

**Not needed, and superseded.** The diagnostic came back as Cost A, and per-element editing
([09](09-element-editing.md)) later removed the problem entirely by making the edited text small.
Kept here because the reasoning explains why element editing was the better answer.

Had the diagnostic shown Cost B, the document would have had to stop being one text field:

- Split the document into blocks at top-level headings — natural once nodes carry source
  ranges ([04](04-org-core.md)).
- Render blocks in a `LazyColumn`. Only the block containing the caret is a real editable
  text field; the rest are static, pre-styled `Text`.
- On focus change, splice the edited block's text back into the document by its range.

This is how a large file becomes instant, and it is what editors built on Compose end up
doing. Budget for the UX consequences, which are the real cost: selection across block
boundaries, find-and-replace spanning blocks, caret movement over edges, and undo across
splices. Prototype those four before committing.

Those four UX costs are exactly what per-element editing avoids: they all come from wanting the
split to be *invisible*. Making it an explicit "edit this item" removes the requirement rather than
solving it.

## Editor surface

**Settled by implementation. Two constraints were found the hard way; both are load-bearing.**

### The state-based API cannot be used

An earlier draft of this document recommended migrating to `BasicTextField(state = TextFieldState)`.
**That is a dead end for a highlighted editor.** The state-based API has no styling hook at all:
`OutputTransformation` rewrites *text*, `codepointTransformation` is a 1:1 codepoint mapping, and
neither can attach spans. Verified against the Compose Multiplatform version in
`libs.versions.toml`.

Syntax highlighting therefore requires the classic `value`/`onValueChange` field and a
`VisualTransformation`, and we accept its costs: the string round-trip per keystroke, and the
constraint below.

The editor uses `BasicTextField` rather than `OutlinedTextField`; the whole-file editor needed its
`onTextLayout` to find the visible range for viewport highlighting, and the element editor kept it. The Material
look is preserved with `OutlinedTextFieldDefaults.DecorationBox` — pass it
`VisualTransformation.None`, or it runs the highlighter a second time on every keystroke just to
decide whether to show a placeholder.

### Editor styling must not change text metrics

**A span that changes font size, weight, style or family makes the text field mis-place its caret on
the keystroke that first applies it.**

It surfaces as "typed spaces are swallowed": typing the space that turns a line into a heading
changes the line's metrics, the caret fails to advance, and because a space has no glyph nothing
appears to happen until the next character re-lays the line out. Saved text was always correct — it
is purely visual.

Bold is **not** exempt. Bold selects a different font face with its own metrics, even within a
monospace family; that was tested and disproved, not assumed.

Reproduced on desktop, iOS and web — the three targets sharing the Skia text backend. Android uses a
different one and is unaffected.

`editorSpanStyle` in `OrgSyntaxHighlighting.kt` therefore keeps **colour, background fills and
underline/strikethrough** — all painted over the same glyphs — and drops everything else. Reading
mode does not go through the transformation and keeps the full styling, heading sizes included.

Consequences to keep in mind:

- Heading levels in the editor can only be distinguished by colour or background, not size or weight.
- Any future editor decoration must pass the same test. `OrgEditorMetricsTest` enforces it.
- Viewport scoping got *simpler* because of this: with nothing varying metrics, off-screen lines could
  be left entirely unstyled without the document shifting as the window moved. (Retired with the
  whole-file editor in T22.)

The only way to lift the constraint is to stop using a text field for display — render styled text
and overlay a transparent field for input — which means owning caret placement, selection handles
and IME behaviour by hand. Not worth it to win back bold.

### What the classic field still costs

The `String`-valued field copies the whole document through `onValueChange` on every keystroke and
invalidates the field. At 45 KB this is measurable but not dominant — the ~27 ms floor is text
layout, not the copy. Per-element editing ([09](09-element-editing.md)) removes it as a concern by
making the edited text small.

### Viewport highlighting

**Retired by T22** along with the whole-file editor, the only surface large enough to need it; kept
here as the record of how it worked. The element editor highlights all of its text, through the same
per-line cache.

Highlight what is on screen, not the whole document:

1. From the layout, get the visible character range.
2. Expand to whole lines, and to enclosing block/drawer boundaries so multi-line
   constructs colour correctly at the edges.
3. Ask `shared/` for `doc.spans(visibleRange)` ([04](04-org-core.md#one-grammar-two-consumers)).
4. Map `OrgSpanKind` → `OrgColors` and apply.

Re-parsing the whole document on each keystroke is acceptable to begin with — a single-pass
line scanner is much cheaper than styling every line — provided it is debounced and
cancellable (`debounce(50)` + `collectLatest`). Consider incremental parsing only if
profiling on a real device says so; on wasm, chunk it with `yield()` so frames still render.

### Reading mode at scale

**Done.** Read mode is a single `LazyColumn` keyed by element, so only what is on screen composes: first
composition of the largest real file went from 234 ms to 38 ms ([09](09-element-editing.md#known-gaps)).

The constraint that shaped it is worth keeping in mind for any screen like it: **a `LazyColumn`
cannot live inside a `Modifier.verticalScroll` column**, which is how `App.kt` was structured. The
screen now hands its scroll to whichever component owns it — reading mode, the search results and
the calendar — and keeps it for everything else. Mapping a scroll offset across read and
edit never became necessary: per-element editing removed the transition between two full-document
renderings.

### Structural editing from reading mode

The plan was the same operations from both modes: long-press a heading to cycle TODO, swipe to
reschedule, keyboard shortcuts in the editor. With one surface left, what shipped is taps and menus
in reading mode ([09](09-element-editing.md#the-surface)), each a range replacement that reformats
nothing else:

- **Tap a checkbox**: `toggleCheckboxAt` finds the node at the tapped offset and ticks it with
  `tickAsOrgDoes`, which also settles the boxes above and below it as Org does.
- **Tap a TODO keyword**: a menu of the file's keywords; picking one goes through `changeTodoState`,
  which logs the change under the heading and repeats a repeating entry. It replaced a tap that
  cycled to the next keyword.
- **Long-press a heading**: the menu adds, moves, promotes, demotes and deletes. These work on
  element ranges (`withOrgSubtreesSwapped`, `withOrgSubtreeShifted`) rather than through `org.edit`.
- **Tap a table**: the grid editor ([09](09-element-editing.md#tables)).

Rescheduling is done in the editor rather than by a swipe: with the caret on a `SCHEDULED:` or
`DEADLINE:` stamp, completion offers a date and time picker opened on it (see the trigger table
below). There are no structural keyboard shortcuts. A tick and a keyword change both update the
statistics cookies over what they changed, as a save does.

`org.edit` also has `setTodo`, `cycleTodo`, `setTags`, `setPlanning`, `promote` and `demote` from T7.
The app does not call them, apart from what `changeTodoState` uses; they are tested and ready for a
caller.

## Completion (G3)

**Done (T13).** Keep the engine pure and multiplatform; keep the popup dumb. The shapes below are
what shipped, with two additions: `CompletionItem.Kind` includes `DATE_PICKER` for the one
suggestion that has to ask before it can answer, and `applyTo` performs an item — the splice plus
the caret — so the editor and the tests do it the same way.

```kotlin
package com.korgnote.common.org.complete

data class CompletionContext(
    val todoKeywords: OrgTodoConfig,   // from this document's #+TODO:, else the global default
    val knownTags: List<String>,       // harvested from the whole replica, most-used first
    val filePaths: List<String>,       // for [[file:…]]
    val currentPath: String,           // the note being edited; file: links are relative to it
    val noteIds: Map<String, String>,  // a note's own :ID:, linked as id: when it has one
    val noteTitles: Map<String, String>, // a note's #+TITLE:, as the link's description
    val headings: List<String>,        // for [[*Heading]] within the document
    val knownLocations: List<String>,  // :LOCATION: values across the replica, most-used first
    val author: String,                // from Settings, for #+AUTHOR:
    val today: LocalDate,
)

data class CompletionItem(
    val label: String,                 // shown in the popup
    val insert: String,                // text to put in
    val replace: IntRange,             // what it replaces (the typed prefix)
    val kind: Kind,
    val caretOffset: Int = insert.length,  // e.g. land inside <…> for a timestamp
)

fun complete(doc: OrgDocument, cursor: Int, ctx: CompletionContext): List<CompletionItem>
```

Triggers, all derived from the current line — and the one above, for a planning line — with
`doc.nodeAt<OrgBlock>(cursor)` to tell when the caret is in a block's body:

| Typing | Suggestions |
|---|---|
| `* ` / `** ` at heading start | TODO keywords from `#+TODO:` (`TODO`, `NEXT`, `WAITING`, `DONE`, …) |
| `SCHE…`, `DEAD…` on the line after a heading | `SCHEDULED: <today>`, `DEADLINE: <today>` |
| after `SCHEDULED:`/`DEADLINE:` | a date-and-time picker entry (a time can be given an end, `10:00-11:30`), and date snippets — today, tomorrow, `+1w`, weekday names |
| caret on a `SCHEDULED:`/`DEADLINE:` stamp already written | the picker, opened on that stamp, then the same snippets; all replace the whole stamp and keep its repeater and warning |
| `:` at end of a heading | tags used elsewhere in the corpus, most frequent first |
| `#+` at line start | `TITLE`, `FILETAGS`, `TODO`, `STARTUP`, `BEGIN_SRC`, … |
| after `#+FILETAGS:` | the corpus's tags, written `:a:b:`, leaving out those already there |
| after `#+AUTHOR:` | the author Settings names |
| after an empty `#+DATE:` | today as an inactive stamp, as org-roam dates a note |
| after `:LOCATION:` | places the notes already name, most named first |
| `[[` | notes — by `id:` when the note has an `:ID:`, else a `file:` path relative to this note, described by its title — then headings in the current document |
| `<` in body text | active timestamp for today; `[` for inactive |
| `<s`, `<q`, `<e` at line start | structure templates (src/quote/example blocks), Org's `org-tempo` convention |

Being pure functions of `(document, cursor, context)`, every row above is a unit test with
no UI involved — 41 of them in `OrgCompleteTests`, plus `CompletionContextTest` (6) for how the
context is built and `CompletionEditingTest` (17) for accepting, the keyboard, the picker and the
insert buttons. The rules are tried in a fixed order, which is what keeps `[[` a link rather than a
bracket and `<s` a template rather than a timestamp; inside a block's body nothing is offered at all,
because there the text is not Org and the parser already knows it.

The strip renders the list, and the field handles Tab (accept), ↑/↓/←/→ (move) and Esc (dismiss
until the next keystroke). Accepting calls `applyTo`, which replaces the item's range and places
the caret — inside `<…>`, or between the two lines of a block.

Platform note, revised: a floating popup suits desktop and a strip suits phones, and **both get the
strip**. The popup fights the IME and the selection handles, phones are where these notes are
written, and one presentation is one thing to keep working. Desktop loses nothing, because the
keyboard path does not go through the chips.

### Where the strip goes, and why it matters

First attempt put the strip under the field, and on a phone it was frequently invisible. The reason
is worth keeping: the app is inset for the keyboard already — `MainActivity` wraps everything in
`imePadding()`, and iOS lifts the focused field — but both of those bring **the field** into view,
and the strip was laid out after it, inside the same scrolling list. Scrolling stops as soon as the
field is visible, which leaves whatever follows it in the region the keyboard just took.

So the strip is pinned to the bottom of the screen, as the `Scaffold`'s `bottomBar`, for as long as
an element is being edited. Three consequences, all deliberate:

- **The caret lives in the view model.** The strip is nowhere near the field, so the text *and* the
  caret are hoisted: `elementDraftValue` is a `TextFieldValue` the field is fully controlled by, and
  the suggestions are computed from it there. Accepting one becomes an ordinary state change rather
  than a message pushed down into a composable — and testable, which the caret handling was not.
- **The navigation bar is hidden while editing.** It occupied that space, and switching tabs in the
  middle of an edit only ever happened by accident.
- **The bar holds its place even with nothing to suggest.** Suggestions appear and vanish with
  almost every keystroke; a bar that came and went with them would reflow the document under the
  caret each time. With nothing to suggest it shows insert buttons (`DraftInsert` in
  `shared/…/org/edit/DraftInserts.kt`): link, date, tag, schedule, place, checkbox and table. Each
  writes the start of its syntax and leaves the caret where the suggestions take over — after the
  heading's tag colon, `SCHEDULED:` or `:LOCATION:` — so the rest is completion's job, as when it is
  typed. Link opens a finder over every note and heading, date the picker, and table the grid editor
  ([09](09-element-editing.md#tables)); with the caret on a link, *Edit link* leads. Tag, schedule
  and place are offered only on a heading. Save and Discard sit at the bar's end.

The context comes from outside the document being edited: completion runs against a single element,
while the tags are the whole replica's and the `#+TODO:` keywords are the open file's. The view
model rebuilds it when the replica or the open file changes — not per keystroke.

### Lists continue on Enter

Enter at the end of a list item starts the next one, as Org's `M-RET` does: the same bullet, the next
number of an ordered list, an empty box after a checkbox (`continueList` in
`shared/…/org/edit/ListContinuation.kt`). Enter in the middle of an item splits it. Enter on an
empty item ends the list instead, so there is a way out of one. It does nothing inside a `SRC`,
`EXAMPLE`, `EXPORT` or `COMMENT` block, where the text is code and a `- ` line in YAML is not an Org
list. Covered by `ListContinuationTest`.

### Known gap: suggestions are in English

The app is translated into French, but the engine's labels live in `shared/` and are English only:
`Pick date & time…`, `today`, `tomorrow` and the weekday names in `OrgComplete.kt`. The names are
also what typing narrows by, so `fri` finds Friday and `ven` finds nothing. What gets inserted is
right either way, since Org writes English day names whatever the locale. Fixing it means the engine
taking its labels from the caller, as it already takes the date.

## Search (G2)

**Done (T14).** Because the client holds the whole corpus locally, search is a local problem.
`shared/org/search` holds all of it; the screen only lists what it returns.

- **A linear scan**, as planned. Body text, heading titles and file names in one pass,
  case-insensitive throughout. A ~4 MB corpus — larger than the real notes directory — matches in
  well under a second, so there is no index to keep correct on every save (G7). If that ever stops
  being true, an in-memory inverted index rebuilt on load comes before SQLite/FTS.
- **Results carry location**: file, heading breadcrumb, line, and the character offset. The offset
  is what makes a result openable: the element containing it is the one to show, which is the same
  lookup the calendar jump uses.
- **The query language is words and tags.** Every term must match, so adding one narrows; a quoted
  phrase is one term. `tag:work` — or `:work:`, as a heading spells it — keeps only headings
  carrying the tag, **inherited** from parent headings and `#+FILETAGS:` the way Org inherits it.
  A tag term on its own lists the tagged headings; with words, it says where to look for them.
- **Structured queries later**, mirroring the org-agenda habits worth keeping:
  `todo:NEXT deadline:<=7d`. The parser already splits prefixed terms from words, so another
  prefix is a case in `SearchQuery.parse` and a predicate in `searchOrgFile`; the same evaluator
  should then power saved searches and agenda filters (T16).

`filterQuery` became the degenerate case, as intended: the drawer parses the same query, matches
file names with it, and understands `tag:` by asking whether a file has any hit at all.

**Both boxes search once the typing stops**, after a 200 ms pause, rather than on every keystroke.
The query updates on the keystroke, so the field never lags; only the scan waits. Measured on the
real corpus, a scan is 10–20 ms on a laptop and several times that on a phone, and typing
`groceries` per character spends nine scans to answer prefixes nobody asked about. Clearing the box
is immediate — an empty box means an empty list now — and results already on screen stay while the
next scan is pending, since they answer what was asked a moment ago and a blank list would flicker
on every letter.

The debounce made the search screen feel right and left the drawer sluggish, which located the
real cost there: **it was rendering, not searching.** `FileTree` was a `LazyColumn` holding a single
item with the whole recursive tree inside it, so laziness bought nothing and all 366 rows composed
on every rebuild — and its expansion state was keyed by folder *object identity*, which a rebuilt
tree invalidates, so each keystroke also re-opened every folder and restarted every animation. The
reading-mode problem of [09](09-element-editing.md#known-gaps), in a second place, with the same
fix: the tree is flattened into `visibleRows` — a pure function, so the nesting rules are testable
now that they are a list rather than a shape made of composables — and drawn as real lazy items,
keyed by path. Paths survive a rebuild; object identities do not.

One more cost was measured and is now gone: `searchOrgFile` used to split a file into elements before
it knew whether the file contained the words at all. It now checks the raw text first and passes over
a file missing any term, word or tag, unparsed. It is the same case-insensitive test applied per
line, so it never drops a real hit. The estimate beforehand was a scan cut from 12 ms to 4 ms
(search) and 10 ms to 4 ms (drawer filter).
