# 06 — Editor: scale, completion, search

Addresses G2, G3 and defect D6. Depends on the source-mapped model from
[04-org-core.md](04-org-core.md).

Per-element editing ([09](09-element-editing.md)) is the only editing surface, which removed document
size as a concern: the whole-file editor this work began with was retired by T22, and with it viewport
highlighting. The metrics constraint and the per-line cache apply to the element editor. Search and
completion are below.

## Where it hurt

| Symptom | Cause | Status |
|---|---|---|
| Typing cost grows with document size | `OrgSyntaxHighlightTransformation.filter` re-highlighted the **entire** document on every keystroke | Fixed — per-line cache + viewport scoping; since T22, edited text is one element |
| Redundant work per keystroke | `filter` ran 3× per keystroke, all on identical text | Fixed — memoised |
| Opening a large file composes everything at once | `OrgText` rendered into a plain `Column` inside an outer `verticalScroll` | Fixed — a `LazyColumn` of elements; see [Reading mode at scale](#reading-mode-at-scale) |
| Programmatic edits are awkward | The `String`-valued field gave no cursor control | Fixed for the element editor — it uses `TextFieldValue` |
| Structural actions are ordinal-based | Checkbox toggling addressed the *N*th checkbox instead of a node | Fixed by T7 — a node is identified by its source offset |

## Performance: where the time actually goes

Documents over ~40 KB were noticeably slow to type in, worst on iOS and wasm. Highlighting the whole
document on every keystroke (Cost A) and laying out all of its text (Cost B) needed completely different
fixes, so the rule was to measure which dominated before choosing. It was Cost A, and the numbers and
what they did not show are in [notes/editor-perf.md](notes/editor-perf.md).

### Do coroutines help?

Not with throughput. **Kotlin/Wasm has no threads**: `Dispatchers.Default` there is the same single event
loop as the main dispatcher, so moving parsing or highlighting into a coroutine changes *when* the work
runs, not how long it takes or who is blocked by it. Highlighting cannot be made asynchronous anyway
while it goes through `VisualTransformation`: the field needs styled text during layout.

What coroutines do buy is scheduling. **Debounce and cancel**: a run of keystrokes collapses to one run
per typing pause, cancelling what it supersedes, as the search box does with a cancelled `delay`.
**Chunk and `yield()`**: work that is genuinely long can yield between chunks so that frames render on wasm,
turning a 400 ms freeze into a progressive update; nothing has needed it yet. **Real parallelism where it
exists**: on the JVM, Android and iOS `Dispatchers.Default` is a real thread pool, so corpus-wide work runs
off the main thread (`NotesViewModel.rebuildDerived`, `NoteLibrary.reload`), and the same code degrades to
cooperative interleaving on wasm instead of breaking. The wins themselves came from doing less.

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

The only way to lift the constraint is to stop using a text field for display — render styled text
and overlay a transparent field for input — which means owning caret placement, selection handles
and IME behaviour by hand. Not worth it to win back bold.

### What the classic field still costs

The `String`-valued field copies the whole document through `onValueChange` on every keystroke and
invalidates the field. At 45 KB this is measurable but not dominant — the ~27 ms floor is text
layout, not the copy. Per-element editing ([09](09-element-editing.md)) removes it as a concern by
making the edited text small.

### Reading mode at scale

Read mode is a single `LazyColumn` keyed by element, so only what is on screen composes: first
composition of the largest real file went from 234 ms to 38 ms ([09](09-element-editing.md#what-reading-mode-costs)).

The constraint that shaped it is worth keeping in mind for any screen like it: **a `LazyColumn`
cannot live inside a `Modifier.verticalScroll` column**, which is how `App.kt` was structured. The
screen now hands its scroll to whichever component owns it — reading mode, the search results and
the calendar — and keeps it for everything else. Mapping a scroll offset across read and
edit never became necessary: per-element editing removed the transition between two full-document
renderings.

### Structural editing from reading mode

With one surface there are taps and menus in reading mode ([09](09-element-editing.md#the-surface)),
each a range replacement that reformats nothing else:

- **Tap a checkbox**: `changes.toggleCheckboxAt` finds the node at the tapped offset and ticks it with
  `tickAsOrgDoes`, which also settles the boxes above and below it as Org does.
- **Tap a TODO keyword**: a menu of the file's keywords; picking one goes through `changeTodoState`,
  which logs the change as the file asks and repeats a repeating entry. It replaced a tap that
  cycled to the next keyword.
- **Long-press a heading**: the menu in [09](09-element-editing.md#the-surface). Moving, promoting,
  demoting and deleting work on element ranges (`withOrgSubtreesSwapped`, `withOrgSubtreeShifted`) rather
  than through `org.edit`. It also offers to make each of the element's stamps active or inactive — up to
  five, those of its heading and text, not of planning lines, drawers or blocks (`toggleableTimestamps`) —
  which swaps only the brackets, both halves of a range together, as `org-toggle-timestamp-type` does.
- **Tap a date**: any timestamp with a real date opens its day in the calendar, active or inactive —
  an inactive date is not in the calendar, but what else happened that day is.
- **Tap a table**: the grid editor ([09](09-element-editing.md#tables)).

Rescheduling is done in the editor, or from the calendar's long press
([07](07-calendar-agenda.md#views)): with the caret on a `SCHEDULED:` or `DEADLINE:` stamp, completion
offers a date and time picker opened on it (see the trigger table below). There are no structural keyboard
shortcuts. A tick and a keyword change both update the statistics cookies over what they changed, as a
save does.

The app calls `changeTodoState`, with what that uses (`setTodo`, `setProperty`), `setPriority`, and
`setPlanning` and `setProperty` through `captureInto`.

## Completion (G3)

The engine is pure and multiplatform, and what shows it is dumb. `CompletionItem.Kind` includes
`DATE_PICKER` for the one suggestion that has to ask before it can answer, and `applyTo` performs an item,
the splice plus the caret, so the editor and the tests do it the same way.

```kotlin
package com.korgnote.common.org.complete

data class CompletionContext(
    val todoKeywords: OrgTodoConfig,   // from this document's #+TODO:, else Org's TODO and DONE
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

fun complete(text: String, cursor: Int, ctx: CompletionContext): List<CompletionItem>
```

Triggers, all derived from the current line — and the one above, for a planning line — with
`nodeAt<OrgBlock>(cursor)`, parsed only once a rule has something to offer, to tell when the caret is in
a block's body:

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
| `<` in body text | active timestamp for today, then the picker; `[` for inactive, and the picker keeps the `[` — the way to an inactive stamp on another day, with no button of its own |
| caret on a stamp in the text (not a planning line) | the picker, opened on that stamp, then **Make inactive** / **Make active** (`STAMP_TOGGLE`, labelled by the strip in the reader's language); a range toggles whole |
| `<s`, `<q`, `<e` at line start | structure templates (src/quote/example blocks), Org's `org-tempo` convention |

Being pure functions of `(text, cursor, context)`, every row above is a unit test with
no UI involved, in `OrgCompleteTests`, with `CompletionContextTest` for how the
context is built and `CompletionEditingTest` for accepting, the keyboard, the picker and the
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
  caret are hoisted: `ElementEditor.draftValue` is a `TextFieldValue` the field is fully controlled by, and
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

Because the client holds the whole corpus locally, search is a local problem.
`shared/org/search` holds all of it; the screen only lists what it returns.

- **A linear scan.** Body text, heading titles and file names in one pass,
  case-insensitive throughout. A ~4 MB corpus — larger than the real notes directory — matches in
  well under a second, so there is no index to keep correct on every save (G7). If that ever stops
  being true, an in-memory inverted index rebuilt on load comes before SQLite/FTS.
- **Results carry location**: file, heading breadcrumb, line, and the character offset. The offset
  is what makes a result openable: the element containing it is the one to show, which is the same
  lookup the calendar jump uses.
- **The query language is words, tags, `todo:` and `priority:`** (`OrgSearch.kt`). Every term must match,
  so adding one narrows; a quoted phrase is one term. `tag:work` — or `:work:`, as a heading spells it —
  keeps only headings carrying the tag, **inherited** from parent headings and `#+FILETAGS:` the way Org
  inherits it; a tag a file's `#+TAGS:` line declares as a group also finds the headings carrying one of
  its members. `todo:NEXT` keeps headings whose own keyword is `NEXT` and `priority:A` those whose own cookie
  is `[#A]`, neither inherited, as in Org. A term of these on its own lists the headings; with words, it
  says where to look for them.
- **Structured queries later**, mirroring the org-agenda habits worth keeping, such as `deadline:<=7d`. The
  parser already splits prefixed terms from words, so another prefix is a case in `SearchQuery.parse` and a
  predicate in `searchOrgFile`; the same evaluator should then power agenda filters.

**The drawer's filter is not search.** It keeps the files whose names hold every word, narrowing the tree
at once, as matching names is a walk over the tree and not a scan of the notes. Finding what is inside the
notes is what search is for.

**The search box scans once the typing stops**, after a 200 ms pause, rather than on every keystroke.
The query updates on the keystroke, so the field never lags; only the scan waits. Measured on the
real corpus, a scan is 10–20 ms on a laptop and several times that on a phone, and typing
`groceries` per character spends nine scans to answer prefixes nobody asked about. Clearing the box
is immediate — an empty box means an empty list now — and results already on screen stay while the
next scan is pending, since they answer what was asked a moment ago and a blank list would flicker
on every letter.

The debounce made the search screen feel right and left the drawer sluggish, which located the
real cost there: **it was rendering, not filtering.** `FileTree` was a `LazyColumn` holding a single
item with the whole recursive tree inside it, so laziness bought nothing and all 366 rows composed
on every rebuild — and its expansion state was keyed by folder *object identity*, which a rebuilt
tree invalidates, so each keystroke also re-opened every folder and restarted every animation. The
reading-mode problem of [09](09-element-editing.md#what-reading-mode-costs), in a second place, with the same
fix: the tree is flattened into `visibleRows` — a pure function, so the nesting rules are testable
now that they are a list rather than a shape made of composables — and drawn as real lazy items,
keyed by path. Paths survive a rebuild; object identities do not.

One more cost was measured and is now gone: `searchOrgFile` used to split a file into elements before
it knew whether the file contained the words at all. It now checks the raw text first and passes over
a file missing any term, word or tag, unparsed. It is the same case-insensitive test applied per
line, so it never drops a real hit. What it measured is in [08](08-roadmap.md#t19--continuous-integration).
