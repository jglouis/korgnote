package com.korgnote.common.sync

import kotlinx.serialization.Serializable

/** What `GET /api/v1/manifest` says about one file; see `docs/05-sync-protocol.md`. */
@Serializable
data class ManifestEntry(val path: String, val size: Long, val mtime: Long, val etag: String)

/** The answer of `GET /api/v1/manifest`, written by the server and read by the apps. */
@Serializable
data class Manifest(
    val generation: Long,
    /** False when this is a delta answered from the journal rather than the whole library. */
    val complete: Boolean,
    val entries: List<ManifestEntry>,
    val deleted: List<String> = emptyList(),
)
