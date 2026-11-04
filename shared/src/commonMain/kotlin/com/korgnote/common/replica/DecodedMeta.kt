package com.korgnote.common.replica

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What says `meta.json` is still the file it was: its modification time and its size. */
internal data class MetaStamp(val modified: Long, val size: Long)

/**
 * `meta.json` as last decoded, kept until the file's [MetaStamp] moves.
 *
 * The sync engine asks for one file's record hundreds of times a cycle, and decoding the whole file
 * for each made a cycle quadratic in the notes held. It is kept here, under the storage, rather than
 * in [DocumentStore]: a folder's storage keeps no metadata at all, and must go on reading as empty
 * after a save; and the file stays the truth, so a second copy of the desktop app on the same replica
 * is seen as soon as it writes, rather than having its records overwritten from a stale copy here.
 *
 * Reads are not under the store's lock, so one can overlap a write. A read takes the stamp before the
 * file, so a write landing between leaves an old stamp on new content, which only costs another
 * decode; and it keeps what it decoded only if nothing was written since it began, or two writes
 * sharing a stamp could leave the older one here.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class DecodedMeta {
    private class Known(val stamp: MetaStamp, val meta: ReplicaMeta)

    private val known = AtomicReference<Known?>(null)

    /**
     * What the file at [stamp] holds. Empty when [decode] gets nothing from it, which is not kept: a
     * read can fail for a moment, and the file would look empty until something next wrote to it.
     */
    fun read(stamp: MetaStamp, decode: () -> ReplicaMeta?): ReplicaMeta {
        val seen = known.load()
        if (seen?.stamp == stamp) return seen.meta
        val meta = decode() ?: return ReplicaMeta()
        known.compareAndSet(seen, Known(stamp, meta))
        return meta
    }

    /** [meta] is what the file now holds, at [stamp]: no need to decode it again. */
    fun wrote(stamp: MetaStamp, meta: ReplicaMeta) {
        known.store(Known(stamp, meta))
    }
}
