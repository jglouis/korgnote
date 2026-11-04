# 04 — Org core: one lossless, source-mapped model

Everything here lives in `shared/` and is testable without a UI: the model and spans in
`com.korgnote.common.org` (`OrgMode.kt`, `OrgSyntax.kt`, `OrgSpans.kt`), the edits in
`com.korgnote.common.org.edit`. Defects D4, D5 and D12 ([08](08-roadmap.md#the-defects-the-plan-began-from))
are what it answers.

## The rule

`String.toOrgDocument()` used to throw away the exact source (trailing spaces, blank-line counts,
anything the parser had no node for) and `OrgNode.toOrgString()` guessed it back. Any feature that read a
document, changed one thing and wrote it out therefore rewrote the whole file. That is what produced
the whitespace-churn bug, and it is why toggling a checkbox once had to bypass the model and patch raw
text by ordinal.

The rule that removes this entire class of bug:

> **Parsing never loses information, because the document keeps its own source text.
> Editing is always a text replacement over a source range.**

This is the concrete-syntax-tree approach used by editor toolchains (tree-sitter,
rust-analyzer). It costs one field and buys round-trip fidelity, structural editing,
completion and incremental highlighting.

## The model

```kotlin
package com.korgnote.common.org

sealed class OrgNode {
    var range: IntRange = IntRange.EMPTY    // where its source sits in the document
    open val children: List<OrgNode>        // empty for a leaf
}

data class OrgDocument(override val children: List<OrgNode>, val metadata: List<String>) : OrgNode() {
    var text: String                        // the exact text it was parsed from
    var todoKeywords: OrgTodoConfig         // its own #+TODO: lines, or the fallback
    var todoFallback: OrgTodoConfig         // kept so that an edit's re-parse reads headings the same way
}

/** A heading with everything under it, so its range covers its whole subtree. */
data class OrgSection(
    val title: String,
    val todo: String? = null,               // one of the file's keywords, or null
    val level: Int = 1,
    val tags: List<String> = emptyList(),
    override val children: List<OrgNode> = emptyList(),
    val priority: OrgPriority? = null,      // [#A] or [#10]
) : OrgNode()

fun String.toOrgDocument(fallback: OrgTodoConfig = DEFAULT_TODO_KEYWORDS): OrgDocument
fun orgHeadingOf(line: String, keywords: OrgTodoConfig): OrgHeading
```

`range` and `text` are body properties, not constructor parameters, so they stay out of the data
classes' `equals`; a node built by hand has an empty range. A heading's planning lines and drawers are
children (`OrgPlanning`, `OrgDrawer`), and its title is a string, not a range. `OrgHeading` holds the
parts of one heading line, shared by the parser and anything that must find a heading again.

A heading's own properties are read with `OrgSection.property(key)`, not a sub-heading's, and its
`:ID:` with `OrgSection.ownId`, a blank one being none. A note's title is `orgNoteTitle(text)`: its first
`#+TITLE:` with a value, a line inside a block being the block's text. The link picker, the links
completion writes and anything else that names a note all read it there.

The printer is still `toOrgString()` (`OrgMode.kt`). Harmless, since nothing prints an existing
document back over itself; its only callers outside the printer write a table cell's text.

Invariants, each of which is a test, I3 in a weaker form
([08](08-roadmap.md#t4--nested-headings)):

| # | Invariant |
|---|---|
| I1 | `t.toOrgDocument().text == t` for every input `t`. |
| I2 | For every node, `doc.text.substring(node.range)` is exactly that node's source. |
| I3 | Every line of the document belongs to exactly one leaf node — no gaps, no overlaps. |
| I4 | `doc.replace(r, s).text == doc.text.replaceRange(r, s)`. |

I1 makes the whitespace bug unrepresentable. I3 is what lets a renderer be driven by the parse tree
instead of a second grammar.

## Constructs

Headings (with nesting), TODO keywords, tags, priorities, planning lines
(`SCHEDULED`/`DEADLINE`/`CLOSED`), paragraphs, plain and ordered lists with nesting,
checkbox items, tables, `#+KEYWORD:` lines, comments, drawers (`:PROPERTIES:`, `:LOGBOOK:`),
blocks (`#+BEGIN_…`/`#+END_…`), LaTeX environments, horizontal rules, description lists, footnote
definitions, fixed-width lines (`: text`), and `[@n]` item counters. A list runs on over one blank line
between items and ends at two, as in Org.

Inline (spans, not nodes — see below): emphasis, code/verbatim, links, URLs, timestamps,
footnote references, statistics cookies.

Explicitly out of scope: Babel execution, tangling, LaTeX/export semantics, column view,
archiving. Unrecognised text parses as a preserved paragraph/raw node, never as an error.

What the grammar reads, where it might be guessed otherwise:

- **A heading is stars, then a space**, as `org-outline-regexp` has it. `*\tTitle` is plain text, and so
  is `*bold* text`. `* ` with nothing after it is a heading with no title yet; the parser reads the
  line before trimming it, since the trailing space is what makes it one.
- **Keywords may be indented.** `  #+TITLE: x` is a keyword, as in Emacs and the highlighter
  (`OrgSyntax.keyword`, `titleKeyword`, `fileKeyword`).
- **A block closes on its own end.** `OrgSyntax.closesBlock(line, type)` says so in one place: a named
  `#+END_x` has to name the block, and an empty `#+END_` closes any. The parser, the highlighter, the
  splitter, the agenda, pasting a link and continuing a list all ask it.
- **A link's type is `OrgSyntax.linkScheme`**, in any case, with no space after the colon: `Mu4e:msgid:…`
  is a link type, and `[[Note: see below]]` is a fuzzy link.
- **Priorities** are `OrgPriority`, as Org holds them: a letter `A`–`Z` is its character code and a
  number from 0 to 64 is itself, so the two never meet, and a smaller value is higher. A file's range
  comes from `#+PRIORITIES: highest lowest default` (`prioritiesOf`, `OrgPriorities.kt`), or Org's `A C B`
  when it sets none, or none it can: the three must be alike, in order, the default between.
- **Logging** comes from the `(w@/!)` of each `#+TODO:` word and the `#+STARTUP:` options `logdone`,
  `lognotedone`, `logrepeat`, `lognoterepeat`, `nologrepeat`, `logdrawer` and their opposites
  (`todoLoggingOf`, `TodoLogging.kt`). What it does is in [09](09-element-editing.md#state-changes-and-what-they-log).
- **Entities**, `\alpha` or `\to{}`, and `H_{2}O`-style scripts and `<<targets>>` are read for reading
  mode only (`OrgEntities.kt`: `orgEntityAt`, `orgScriptAt`, `orgTargetAt`), a subset of `org-entities`;
  a name not there stays as typed. The file is untouched either way.
- **`#+LINK:` abbreviations and `doi:`** are read when a link is followed, in `OrgLinks.kt`; see
  [09](09-element-editing.md#links-between-notes).

## Editing API

```kotlin
package com.korgnote.common.org.edit

fun OrgDocument.replace(range: IntRange, replacement: String): OrgDocument    // I4
fun OrgDocument.allNodes(): List<OrgNode>
inline fun <reified T : OrgNode> OrgDocument.nodeAt(offset: Int): T?          // the innermost T there

fun OrgDocument.toggleCheckbox(item: OrgCheckboxItem): OrgDocument
fun OrgDocument.setCheckbox(item: OrgCheckboxItem, checked: Boolean): OrgDocument
fun OrgDocument.tickAsOrgDoes(item: OrgCheckboxItem): OrgDocument             // settles the boxes around it

fun OrgDocument.setTodo(heading: OrgSection, keyword: String?): OrgDocument
fun OrgDocument.changeTodoState(heading, keyword, at, keywords, note): OrgDocument   // logs and repeats, as C-c C-t
fun OrgDocument.stateChangeLog(heading, keyword, keywords): LogKind?          // what that would record
fun OrgDocument.setPriority(heading: OrgSection, priority: OrgPriority?): OrgDocument
fun OrgDocument.setPlanning(heading: OrgSection, kind: PlanningKind, timestamp: String?): OrgDocument
fun OrgDocument.setProperty(heading: OrgSection, key: String, value: String): OrgDocument
```

`Clocking.kt` adds `clockIn`, `clockOut`, `cancelClock` and `setEffort` ([11](11-clocking.md)). Every one of
these computes a range and calls `replace`. Consequences: the rest of the file is untouched by
construction, each function is a two-line test (`before` → `after` text), and the UI can apply the same
operations from wherever it is. Each takes the node it acts on: ordinals are fragile, shifting whenever
anything above them changes. Moving, promoting and deleting a subtree work on element ranges instead
([09](09-element-editing.md#the-model)), since an element is what the screen holds.

## One grammar, two consumers

`OrgSyntaxHighlighting.kt` used to re-implement Org syntax for colouring (D5). It paints from shared
spans instead. They come from a line scanner over the same `OrgSyntax` patterns the parser uses, rather
than from the parse tree itself:

```kotlin
package com.korgnote.common.org

enum class OrgSpanKind { Heading, Stars, Todo, Done, Priority, Tag, Planning, PlanningKeyword, Keyword,
    Comment, Drawer, DrawerKey, DrawerValue, BlockDelimiter, BlockBody, Bullet, Checkbox, TableLine,
    Timestamp, Footnote, Statistics, LinkMarkup, LinkTarget, LinkDescription, Url, Bold, Italic,
    Underline, StrikeThrough, Verbatim, Code, Markup, /* and a few more; Code* in a src block */ }

data class OrgSpan(val range: IntRange, val kind: OrgSpanKind, val nesting: Int = 0)

fun orgSpans(text: String): List<OrgSpan>
fun orgSpansForLine(line: String, state: OrgLineState, classify: Boolean = true): List<OrgSpan>
```

`shared/` decides *what* something is; `composeApp/` decides what colour that is, through the `OrgColors`
palette, with `OrgSyntaxHighlightingTest` as the contract.

**Second definitions.** Org patterns crept back outside the one grammar after T6. Most are gone again:
`:ID:` is `OrgSyntax.idProperty`; `#+AUTHOR:` and `#+DATE:` are `OrgSyntax.fileKeyword`; `#+TITLE:` is
`orgNoteTitle`; a link's scheme is `OrgSyntax.linkScheme` and `fileLinkSchemes`, in `LinkNavigator`,
`LinkEditor` and `LinkEditing` alike; a list item in `ListContinuation` is `OrgSyntax.listItem`; a line a
pasted link stays bare on is `OrgSyntax.keyword` or `OrgSyntax.property`; a heading line's tags are
`OrgSyntax.tags`; and a `#+TBLFM:` target and format are read in `shared/…/org/table/`, not in the UI.
What remains:

| Where | Pattern | Why it stays |
|---|---|---|
| `composeApp/…/ui/OrgSyntaxHighlighting.kt` | `timestampDay` | Not equivalent, kept apart on purpose. `\d{4}-\d{2}-\d{2}` reads the date out of a stamp `OrgSyntax.timestamp` has found, brackets and all; it does not recognise one. |
| `shared/…/org/edit/FileKeywords.kt` | `KEYWORD_LINE` | Not equivalent, kept apart on purpose. It wants a name and a colon, `#+TITLE:`, to place a new keyword after the last one; `OrgSyntax.keyword` is any `#+`, a block's `#+BEGIN_SRC` included. |
| `composeApp/…/ui/OrgSyntaxHighlighting.kt` | `TAG_NAME` | One tag inside a run `OrgSyntax.tags` has found. |
| `shared/…/org/edit/DraftInserts.kt`, `shared/…/org/link/LinkTargets.kt` | `PROPERTIES` | The same line twice: `OrgSyntax.drawerBegin` takes any drawer, and nothing names this one. Harmless, and the one real second definition left. |

## Testing

What exists: `OrgSourceRangeTests` asserts I1 and I2 over hand-written edge cases on every platform,
`OrgFixtureRoundTripTest` over the samples in `server/src/test/resources/org`,
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
