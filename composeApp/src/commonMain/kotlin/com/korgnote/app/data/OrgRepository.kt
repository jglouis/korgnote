package com.korgnote.app.data

import com.korgnote.common.fs.Folder

interface OrgRepository {
    // Returns map of filename -> raw file text; parsing happens on this side.
    suspend fun fetchAllContents(): Map<String, String>

    // Returns a serializable tree rooted at Documents
    suspend fun fetchTree(): Folder

    // Returns the exact raw text of a single file, as stored on disk
    suspend fun fetchRawContent(path: String): String

    // Saves the content of a file
    suspend fun saveContent(path: String, content: String)
}
