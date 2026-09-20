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
- **A caret you can program against.** `state.edit { replace(start, end, text); placeCursorAfterCharAt(…) }`
  is what completion insertion and structural commands need.
- **Undo/redo** for free.

### Viewport highlighting

Highlight what is on screen, not the whole document:

1. From the layout, get the visible character range.
2. Expand to whole lines, and to enclosing block/drawer boundaries so multi-line
   constructs colour correctly at the edges.
3. Ask `shared/` for `doc.spans(visibleRange)` ([04](04-org-core.md#one-grammar-two-consumers)).
4. Map `OrgSpanKind` → `OrgColors` and apply.

Re-parsing the whole document on each keystroke is acceptable to begin with — a single-pass
line scanner over a few hundred KB is fast, and the win here is not re-*styling* megabytes.
Debounce re-parse by ~50 ms, and only consider incremental parsing if profiling on a real
device says so.

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
