# 04 — Org core: one lossless, source-mapped model

Addresses D4, D5, D12. Everything here lives in `shared/` and is testable without a UI.

## The problem

Today `String.toOrgDocument()` throws away the exact source (trailing spaces, blank-line
counts, anything the parser has no node for) and `OrgNode.toOrgString()` guesses it back.
Any feature that reads a document, changes one thing and writes it out therefore rewrites
the whole file. That is what produced the whitespace-churn bug, and it is why toggling a
checkbox currently has to bypass the model and patch raw text by ordinal
(`OrgMode.kt:305`).

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

The ordinal-based `String.toggleNthCheckbox` added by the in-flight fix is replaced by
`toggleCheckbox(node)`. Ordinals are fragile — they shift whenever anything above them
changes — and only exist today because the renderer had no node identity to hand back.

## One grammar, two consumers

`OrgSyntaxHighlighting.kt` currently re-implements Org syntax for colouring (D5). After
this change it consumes the parse tree instead:

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
existing `OrgColors` palette and the `OrgSyntaxHighlightingTest` expectations carry over —
only the producer changes.

## Migration path

The parser has 12 test files; treat them as the safety net and move in steps that each keep
the suite green.

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
