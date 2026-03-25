package com.korgnote.app.data

import com.korgnote.common.fs.Folder
import com.korgnote.common.org.OrgDocument

interface OrgRepository {
    // Returns map of filename -> parsed OrgDocument
    suspend fun fetchAllContents(): Map<String, OrgDocument>

    // Returns a serializable tree rooted at Documents
    suspend fun fetchTree(): Folder

    // Saves the content of a file
    suspend fun saveContent(path: String, content: String)
}
