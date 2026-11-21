package com.korgnote.common.sync

import kotlinx.serialization.Serializable

/**
 * Describes one file recorded in a synchronization manifest.
 *
 * The entry identifies a file by its manifest path and stores the metadata needed to compare local
 * and remote state, including byte size, last modification time, and entity tag.
 */
@Serializable
data class ManifestEntry(val path: String, val size: Long, val mtime: Long, val etag: String)

/**
 * Describes a snapshot or delta of files known to the synchronization system.
 *
 * A manifest identifies the library generation it was produced from, reports whether it represents
 * the complete library state or only changes since an earlier generation, lists files currently
 * present, and lists manifest paths that were deleted when the manifest is a delta.
 */
@Serializable
data class Manifest(
    val generation: Long,
    /** False when this is a delta answered from the journal rather than the whole library. */
    val complete: Boolean,
    val entries: List<ManifestEntry>,
    val deleted: List<String> = emptyList(),
)
