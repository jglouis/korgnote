# 06 — Editor: scale, completion, search

Addresses G2, G3 and defect D6. Depends on the source-mapped model from
[04-org-core.md](04-org-core.md).

## Where it hurts today

| Symptom | Cause |
|---|---|
| Typing cost grows with document size | `OrgSyntaxHighlightTransformation.filter` re-highlights the **entire** document on every keystroke (`OrgSyntaxHighlighting.kt:481`) |
| Opening a large file composes everything at once | `OrgText` renders into a plain `Column` (`OrgText.kt:88`) inside an outer `verticalScroll` (`App.kt`) |
| Programmatic edits are awkward | The `String`-valued `OutlinedTextField` gives no cursor/selection control, so commands cannot insert at the caret |
| Structural actions are ordinal-based | Checkbox toggling addresses the *N*th checkbox instead of a node |

## Performance: where the time actually goes

Observed today: documents over ~40 KB are noticeably slow to type in, worst on iOS and
wasm. Two independent costs are in play, and they need completely different fixes, so
**measure which one dominates before choosing**.

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

If the diagnostic shows Cost B, the document must stop being one text field:

- Split the document into blocks at top-level headings — natural once nodes carry source
  ranges ([04](04-org-core.md)).
- Render blocks in a `LazyColumn`. Only the block containing the caret is a real editable
  text field; the rest are static, pre-styled `Text`.
- On focus change, splice the edited block's text back into the document by its range.

This is how a large file becomes instant, and it is what editors built on Compose end up
doing. Budget for the UX consequences, which are the real cost: selection across block
boundaries, find-and-replace spanning blocks, caret movement over edges, and undo across
splices. Prototype those four before committing.

An interim measure, if virtualisation is too big a step: keep the full text field for files
under a threshold (say 100 KB) and fall back to block mode above it. Ugly, but it buys time
and matches how the slowness actually presents.

## Editor surface

Move to the state-based text field API (Compose Multiplatform 1.12 has it):

```kotlin
val state = rememberTextFieldState(initialText = document.text)

BasicTextField(
    state = state,
    inputTransformation = null,
    outputTransformation = null,          // highlighting no longer goes through a transformation
    textStyle = monospace,
    modifier = Modifier.fillMaxSize(),
)
```

What this buys:

- **No full-string recomposition per keystroke.** `TextFieldState` mutates a buffer; the
  UI observes edits rather than round-tripping an immutable `String` through the ViewModel.
  This removes the 40 KB copy-and-invalidate described in Cost A.
- **A caret you can program against.** `state.edit { replace(start, end, text); placeCursorAfterCharAt(…) }`
  is what completion insertion and structural commands need.
- **Undo/redo** for free.

What it does **not** buy: a solution to syntax highlighting. The state-based API changes how
text is edited, not how spans are applied, and the styling hooks differ between Compose
versions — check what the version in `libs.versions.toml` actually offers before designing
around it. Scoping the styling to the viewport (below) is the part that matters, and it is
required either way.

### Viewport highlighting

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

Read mode should be a single `LazyColumn` keyed by top-level heading, so only visible
subtrees compose. Constraint worth knowing before starting: **a `LazyColumn` cannot live
inside a `Modifier.verticalScroll` column**, which is how `App.kt` is currently structured
(and the shared scroll position between read and edit mode was a deliberate recent choice —
see the comment added in the "Link :LOCATION:" commit). The screen must be restructured so
that exactly one component owns scrolling in each mode, and the scroll offset is mapped
across the two by *character offset* rather than pixels.

### Structural editing from both modes

With `org.edit` in place, the same operations are available from the reader (tap a checkbox,
long-press a heading to cycle TODO, swipe to reschedule) and the editor (keyboard
shortcuts). Both call `DocumentStore.apply(doc.toggleCheckbox(node))`; neither reformats
anything else.

## Completion (G3)

Keep the engine pure and multiplatform; keep the popup dumb.

```kotlin
package com.korgnote.common.org.complete

data class CompletionContext(
    val todoKeywords: OrgTodoConfig,   // from this document's #+TODO:, else the global default
    val knownTags: List<String>,       // harvested from the whole replica, most-used first
    val filePaths: List<String>,       // for [[file:…]]
    val headings: List<String>,        // for [[*Heading]] within the document
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

Triggers, all derived from `doc.nodeAt(cursor)` plus the current line prefix:

| Typing | Suggestions |
|---|---|
| `* ` / `** ` at heading start | TODO keywords from `#+TODO:` (`TODO`, `NEXT`, `WAITING`, `DONE`, …) |
| `SCHE…`, `DEAD…` on the line after a heading | `SCHEDULED: <today>`, `DEADLINE: <today>` |
| after `SCHEDULED:`/`DEADLINE:` | date snippets — today, tomorrow, `+1w`, weekday names — and a date-picker entry |
| `:` at end of a heading | tags used elsewhere in the corpus, most frequent first |
| `#+` at line start | `TITLE`, `FILETAGS`, `TODO`, `STARTUP`, `BEGIN_SRC`, … |
| `[[` | file paths, then headings in the current document |
| `<` in body text | active timestamp for today; `[` for inactive |
| `<s`, `<q`, `<e` at line start | structure templates (src/quote/example blocks), Org's `org-tempo` convention |

Being pure functions of `(document, cursor, context)`, every row above is a unit test with
no UI involved. The popup itself only renders a list, handles ↑/↓/Tab/Esc, and calls
`state.edit { }` with the chosen item's `replace`/`insert`.

Platform note: a floating popup suits desktop; on phones, prefer a suggestion strip pinned
above the keyboard — the popup fights the IME and the selection handles. Same engine, two
presentations.

## Search (G2)

Because the client holds the whole corpus locally, search is a local problem:

- **Start with a linear scan.** A few megabytes of text matches in milliseconds; an index
  is premature. Search text, heading titles and file paths in one pass, case-insensitive,
  with an optional literal/regex toggle.
- **Results carry location**: file, heading breadcrumb, line, and the character offset so
  tapping a result opens the document at exactly that place.
- **Structured queries later**, mirroring the org-agenda habits worth keeping:
  `todo:NEXT tag:work deadline:<=7d text:"invoice"`. Parse into a small query AST in
  `shared/org/search` and evaluate against parsed documents; the same evaluator then powers
  saved searches and agenda filters.
- **Index only if needed.** If the corpus ever outgrows linear scan, add an in-memory
  inverted index rebuilt on load and updated per save, before reaching for SQLite/FTS. Each
  step is a dependency you would have to maintain (G7).

Today's `filterQuery` (`FileTreeViewModel.kt:42`) filters filenames only; it becomes the
degenerate case of the above.
