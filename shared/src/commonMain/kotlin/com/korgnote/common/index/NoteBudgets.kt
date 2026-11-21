package com.korgnote.common.index

/**
 * How much of the heap the notes may take, worked out from what this device gives the app rather than
 * from how many notes there are, which nobody can know: `~/org` may be a shopping list, or a quarter
 * of a gigabyte of generated logs.
 *
 * A library that fits is kept whole, so it is as quick as if there were no limit at all. One that does
 * not fit is read as it is needed.
 */
data class NoteBudgets(
    /** Bytes of note text [com.korgnote.common.index.NoteIndex]'s users may keep at hand. */
    val texts: Long,
    /** Bytes of parsed notes they may keep at hand. */
    val parses: Long,
    /** Bytes of text a slow folder may keep, on top, for what it was just asked for. */
    val folderTexts: Long,
    /** Bytes all the notes' [NoteFacts] may take together. Past that, a note is [LeftOut.NoRoom]. */
    val facts: Long,
    /** The biggest file read into memory at all: past it a note is listed, and says it cannot open. */
    val maxNoteBytes: Long,
) {
    companion object {
        /**
         * Creates note memory budgets from heap-size limits.
         *
         * The cache-based budgets are derived from [caches], while budgets that bound
         * total note facts and the largest readable note are derived from [heap].
         *
         * @param heap total heap bytes available for note-related limits.
         * @param caches heap bytes to use when sizing cached note text, parsed notes, and folder text.
         * @return budgets split across text, parse, folder text, facts, and maximum note size limits.
         */
        fun of(heap: Long, caches: Long = heap) = NoteBudgets(
            texts = caches / 4,
            parses = caches * 3 / 20,
            folderTexts = caches / 10,
            facts = heap / 5,
            maxNoteBytes = heap / 16,
        )

        fun forThisDevice(): NoteBudgets = of(heapLimitBytes(), cacheHeapOverride() ?: heapLimitBytes())
    }
}

/** The most heap this process can have. */
internal expect fun heapLimitBytes(): Long

/**
 * A heap to size the caches from, instead of the real one, where a test run asks for it: run with next
 * to nothing, every test goes through what happens when a note is not at hand.
 */
internal expect fun cacheHeapOverride(): Long?
