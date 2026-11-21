package com.korgnote.common.replica

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Lightweight record of a file's observable metadata at a point in time.
 *
 * Used to compare a later file state against a previously seen state without reading the file
 * contents. A change in either timestamp or size means the file may have changed.
 */
internal data class MetaStamp(val modified: Long, val size: Long)

/**
 * Caches the decoded contents of a replica metadata file for the last observed file stamp.
 *
 * The cache avoids decoding `meta.json` repeatedly when its observable file metadata has not
 * changed. A cached value is reused only when the requested [MetaStamp] matches the stamp stored
 * with the decoded [ReplicaMeta].
 *
 * Failed or empty decodes are not cached. In that case callers receive an empty [ReplicaMeta], while
 * a later read of the same stamp may attempt decoding again. This prevents a transient read failure
 * from making the metadata appear permanently empty until the file is rewritten.
 *
 * Updates are atomic, so concurrent readers and writers may share an instance without external
 * synchronization. Races are benign: a reader may decode and lose the compare-and-set to another
 * update, but it still returns the metadata it decoded for the requested stamp.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class DecodedMeta {
    private class Known(val stamp: MetaStamp, val meta: ReplicaMeta)

    private val known = AtomicReference<Known?>(null)

    /**
     * Reads replica metadata for the given file stamp, reusing the cached decoded value when the
     * stamp matches the last successfully decoded metadata.
     *
     * If no cached value matches, [decode] is invoked. A non-null decoded value is returned and may be
     * cached for [stamp]. A null result is treated as an unavailable or empty decode and returns a new
     * empty [ReplicaMeta] without updating the cache.
     *
     * @param stamp observable file metadata identifying the state to read.
     * @param decode function that decodes the metadata when the cached value cannot be reused.
     * @return the cached or decoded [ReplicaMeta], or an empty [ReplicaMeta] when decoding yields null.
     */
    fun read(stamp: MetaStamp, decode: () -> ReplicaMeta?): ReplicaMeta {
        val seen = known.load()
        if (seen?.stamp == stamp) return seen.meta
        val meta = decode() ?: return ReplicaMeta()
        known.compareAndSet(seen, Known(stamp, meta))
        return meta
    }

    /**
     * Records the replica metadata that was written for the given file stamp, making it the cached
     * known metadata for subsequent reads.
     *
     * @param stamp observable file metadata identifying the state that was written.
     * @param meta replica metadata associated with the written state.
     */
    fun wrote(stamp: MetaStamp, meta: ReplicaMeta) {
        known.store(Known(stamp, meta))
    }
}
