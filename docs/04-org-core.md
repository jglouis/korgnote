# 04 — Org core: one lossless, source-mapped model

Addresses D4, D5, D12. Everything here lives in `shared/` and is testable without a UI.

> **Status: built (T3–T7).** The rule below holds and the four invariants are tested, I3 in the
> weaker form T4 recorded ([08](08-roadmap.md#t4--nested-headings---done)). The sketches are the
> plan; what shipped differs in its names and shapes:
>
> - The model and spans are in `com.korgnote.common.org` (`OrgMode.kt`, `OrgSpans.kt`) and the
>   edits in `com.korgnote.common.org.edit`; there is no `org.model`.
> - A document is parsed with `String.toOrgDocument()`, not `OrgDocument.parse`. `OrgDocument` is a
>   data class holding `children` and `metadata`, with no `OrgRoot`.
> - `range` and `text` are body properties, not constructor parameters, so they stay out of the
>   data classes' `equals`; a node built by hand has an empty range.
> - A heading is `OrgSection(title, todo, level, tags, children, priority)`. Its planning lines and
>   drawers are children (`OrgPlanning`, `OrgDrawer`), and its title is a string, not a range.
>   `OrgHeading` exists, but as the parts of one heading line (`orgHeadingOf`), shared by the parser
>   and anything that must find a heading again.
> - `replace` and `nodeAt` are extensions in `org.edit`, and `nodeAt<T>(offset)` is generic: the
>   innermost node of type `T` there, or null. The edit functions take an `OrgSection`, and
>   `tickAsOrgDoes`, `changeTodoState` and `setProperty` joined them.
> - Spans are `orgSpans(text, within)` and `orgSpansForLine(line, state)`, with
>   `OrgDocument.spans(range)` over them, and a finer `OrgSpanKind`.
> - Step 6 was never done: the printer is still `toOrgString()` (`OrgMode.kt`), not
>   `OrgPrinter.render`. Harmless, since nothing prints an existing document back over itself; its
>   only callers outside the printer write a table cell's text.

## The problem, before T3

`String.toOrgDocument()` threw away the exact source (trailing spaces, blank-line counts, anything
the parser had no node for) and `OrgNode.toOrgString()` guessed it back. Any feature that read a
document, changed one thing and wrote it out therefore rewrote the whole file. That is what produced
the whitespace-churn bug, and it is why toggling a checkbox had to bypass the model and patch raw
text by ordinal (`toggleNthCheckbox`, deleted by T7).

The rule that removes this entire class of bug:

> **Parsing never loses information, because the document keeps its own source text.
> Editing is always a text replacement over a source range.**

This is the concrete-syntax-tree approach used by editor toolchains (tree-sitter,
rust-analyzer). It costs one field and buys round-trip fidelity, structural editing,
completion and incremental highlighting.

## Target model

```kotlin
package com.korgnote.common.org.model

/** A document is its text, plus a tree that points into that text. */
class OrgDocument internal constructor(
    val text: String,
    val root: OrgRoot,
) {
    /** Deepest node containing [offset]; the basis for completion and cursor-aware commands. */
    fun nodeAt(offset: Int): OrgNode

    /** The only mutation primitive. Returns a new document (re-parsed). */
    fun replace(range: IntRange, replacement: String): OrgDocument

    companion object { fun parse(text: String): OrgDocument }
}

sealed interface OrgNode {
    /** Absolute offsets into [OrgDocument.text]. `text.substring(range)` is this node's source. */
    val range: IntRange
    val children: List<OrgNode>
}

/** Headings nest (fixes D12): a section owns its body and its sub-sections. */
class OrgHeading(
    override val range: IntRange,
    val level: Int,
    val todo: String?,          // raw keyword; meaning comes from OrgTodoConfig
    val priority: Char?,        // [#A]
    val title: TextRange,       // range of the title only, for in-place retitling
    val tags: List<String>,
    val planning: List<OrgPlanning>,
    val properties: OrgDrawer?, // :PROPERTIES: … :END:
    override val children: List<OrgNode>,
) : OrgNode
```

Invariants, each of which should be an explicit test:

| # | Invariant |
|---|---|
| I1 | `OrgDocument.parse(t).text == t` for every input `t`. |
| I2 | For every node, `doc.text.substring(node.range)` is exactly that node's source. |
| I3 | Every line of the document belongs to exactly one leaf node — no gaps, no overlaps. |
| I4 | `doc.replace(r, s).text == doc.text.replaceRange(r, s)`. |

I1 makes the whitespace bug unrepresentable. I3 is what lets the highlighter and renderer
be driven by the parse tree instead of a second grammar.

## Constructs to cover

Present: headings (+ nesting), TODO keywords, tags, priorities, planning lines
(`SCHEDULED`/`DEADLINE`/`CLOSED`), paragraphs, plain and ordered lists with nesting,
checkbox items, tables, `#+KEYWORD:` lines, comments, drawers (`:PROPERTIES:`, `:LOGBOOK:`),
blocks (`#+BEGIN_…`/`#+END_…`), horizontal rules, description lists, footnote definitions.

Inline (spans, not nodes — see below): emphasis, code/verbatim, links, URLs, timestamps,
footnote references, statistics cookies.

Explicitly out of scope: Babel execution, tangling, LaTeX/export semantics, column view,
archiving. Unrecognised text must still parse as a preserved paragraph/raw node — never as
an error.

## Editing API

```kotlin
package com.korgnote.common.org.edit

fun OrgDocument.toggleCheckbox(item: OrgCheckboxItem): OrgDocument
fun OrgDocument.setTodo(heading: OrgHeading, keyword: String?): OrgDocument
fun OrgDocument.cycleTodo(heading: OrgHeading, config: OrgTodoConfig): OrgDocument
fun OrgDocument.setPlanning(heading: OrgHeading, kind: PlanningKind, stamp: OrgTimestamp?): OrgDocument
fun OrgDocument.setTags(heading: OrgHeading, tags: List<String>): OrgDocument
fun OrgDocument.promote(heading: OrgHeading): OrgDocument   // subtree-aware
fun OrgDocument.demote(heading: OrgHeading): OrgDocument
```

Every one of these computes a range and calls `replace`. Consequences: the rest of the file
is untouched by construction, each function is a two-line test (`before` → `after` text),
and the UI can apply the same operations from read mode, edit mode or the calendar.

T7 replaced the ordinal-based `String.toggleNthCheckbox` with `toggleCheckbox(node)`. Ordinals
are fragile — they shift whenever anything above them changes — and only existed because the
renderer had no node identity to hand back.

## One grammar, two consumers

`OrgSyntaxHighlighting.kt` used to re-implement Org syntax for colouring (D5). Since T6 it paints
from shared spans instead. They come from a line scanner over the same `OrgSyntax` patterns the
parser uses, rather than from the parse tree itself:

```kotlin
package com.korgnote.common.org.model

enum class OrgSpanKind { Heading, Stars, Todo, Done, Priority, Tag, Planning, Timestamp,
    Keyword, Comment, Drawer, BlockDelimiter, BlockBody, Bullet, Checkbox, TableSeparator,
    Link, LinkTarget, Url, Emphasis, Verbatim, Code, Footnote, Statistics, Markup }

data class OrgSpan(val range: IntRange, val kind: OrgSpanKind, val nesting: Int = 0)

/** Spans for a slice of the document — the slice is what makes viewport highlighting possible. */
fun OrgDocument.spans(range: IntRange): List<OrgSpan>
```

`shared/` decides *what* something is; `composeApp/` decides what colour that is. The
existing `OrgColors` palette and the `OrgSyntaxHighlightingTest` expectations carried over —
only the producer changed.

**Drift to fold back.** Org patterns have crept back outside the one grammar since T6. None breaks an
edit, but each is a second definition that can disagree with the first, and two already do:

| Where | Pattern | Duplicates |
|---|---|---|
| `composeApp/…/ui/OrgText.kt` | `ID_PROPERTY` (`:ID:`) | `ID_PROPERTY` in `shared/…/org/link/LinkTargets.kt` |
| `composeApp/…/ui/OrgText.kt` | `TODO_KEYWORDS_LINE` | `parseTodoConfigFrom` — and **disagrees**: it also reads `#+SEQ_TODO:` and `#+TYP_TODO:`, which the parser ignores, so reading mode shows chips for keywords nothing else treats as keywords |
| `composeApp/…/ui/NoteByline.kt` | `AUTHOR`, `DATE` | `FILE_KEYWORD` in `shared/…/org/edit/FileKeywords.kt` |
| `composeApp/…/viewmodel/LinkNavigator.kt` | `TITLE_LINE`, `SCHEME` | `OrgSyntax.titleKeyword`; `SCHEME` in `edit/LinkEditing.kt` |
| `composeApp/…/ui/LinkEditor.kt` | `FILE_SCHEME`, `HAS_SCHEME` | `FILE_SCHEMES`, `SCHEME` in `edit/LinkEditing.kt` |
| `composeApp/…/ui/OrgSyntaxHighlighting.kt` | `timestampDay` | `OrgSyntax.timestampDate`, without its `<` |
| `composeApp/…/ui/TableFormulas.kt` | `#+TBLFM:` targets (`$3`, `@>$2`) and formats (`;%.2f`) | what `shared/…/org/table/` parses |
| `shared/…/org/edit/ListContinuation.kt` | `LIST_ITEM` | `OrgSyntax.listItem` |
| `shared/…/org/edit/StatisticsCookies.kt` | `STATISTICS_COOKIE` | `OrgSyntax.statistics` — and **disagrees**: only the former matches an empty `[/]` or `[%]`, so the highlighter does not show a cookie as one until a save fills it in |
| `shared/…/org/edit/LinkEditing.kt` | `KEYWORD_LINE`, `PROPERTY_LINE` | `OrgSyntax.keyword`, `OrgSyntax.property` |
| `shared/…/org/edit/FileKeywords.kt`, `shared/…/org/link/LinkTargets.kt` | `KEYWORD_LINE`; `TITLE`, `PROPERTIES` | `OrgSyntax.keyword`; `OrgSyntax.titleKeyword`, the parser's drawers |

The fix is the one T6 made: move each into `OrgSyntax`, or call what is already there.

## Migration path

Steps 1–5 were done as T3–T7; step 6 was not (see the status note).

The parser had 12 test files; they were the safety net, and each step kept the suite green.

1. **Add ranges to the existing nodes** without changing their shape. Store `text` on
   `OrgDocument`. Assert I1 and I2. Nothing else changes yet.
2. **Nest headings** (D12) and move section bodies from a pre-trimmed `content: String` to
   child nodes with ranges. Update `OrgText.kt` to render from the tree.
3. **Add the missing constructs** (drawers, blocks, ordered/nested lists, priorities),
   porting the regexes from `OrgSyntax` so the knowledge is moved, not duplicated.
4. **Introduce `spans()`** and rewrite `OrgSyntaxHighlightTransformation` to use it.
   Delete the duplicate grammar.
5. **Introduce the `edit` package**; port `toggleNthCheckbox` to `toggleCheckbox(node)`.
6. **Restrict the printer.** `toOrgString()` stops being a way to rewrite existing
   documents and survives only as a *builder for new content* (templates, capture, new
   headings) — rename it `OrgPrinter.render` so the distinction is visible at the call site.
   No code path may print an existing document back over itself.

Serialization note: once the client parses locally, `OrgNode` no longer has to be
`@Serializable` — that annotation exists purely to ship ASTs over HTTP, which
[05-sync-protocol.md](05-sync-protocol.md) removes. Dropping it frees the model to use
ranges and non-serialisable shapes freely.

## Testing

- **Round-trip property test**: for a corpus of files (the existing test fixtures, plus
  gnarly hand-written cases — trailing whitespace, CRLF, multiple blank lines, unicode,
  a file that is only blank lines, a 1 MB file), assert I1.
- **Range integrity**: walk the tree, assert I2 and I3.
- **Edit tests**: `before text` + operation → `expected text`, asserting the diff touches
  only the intended line.
- **Golden files** for `spans()` to keep highlighting honest as the grammar grows.
- Optional and high-value: a test that runs the round-trip property over *your real notes
  directory* when an env var points at it — the corpus that matters most.

What exists: `OrgSourceRangeTests` asserts I1 and I2 over hand-written edge cases on every platform,
`OrgFixtureRoundTripTest` over the bundled samples in `server/src/main/resources/org`,
`OrgNestingTests` the weaker I3, `OrgEditTests` the edits, and `OrgSpansTests` with
`OrgSyntaxHighlightingTest` the spans, as assertions rather than golden files. Known gaps:

- **I2 is not checked below the top level.** Both I2 walks (`everyNode` in `OrgSourceRangeTests` and
  `OrgFixtureRoundTripTest`) descend only into `OrgDocument` and `OrgTable`, never into a section's
  children, so a planning line, list or drawer inside a heading is never checked to select its own
  source. `OrgNestingTests` does walk the whole tree, but asserts only that a child sits inside its
  parent and that each line's owners form one chain.
- **No test runs over the real notes directory.** The sweeps recorded in
  [08](08-roadmap.md) for T4, T5 and T7 were one-off runs, not an env-var test.
- **Nothing tests CRLF**, nor a 1 MB file.
- **Table cells still have no source range.** `parseOrgTable` builds them from trimmed cell text
  (`OrgMode.kt`); T3 deferred this to T5, which did not do it. The grid editor replaces whole tables,
  so nothing needs it yet.
