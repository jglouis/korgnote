package com.korgnote.app.data

interface OrgRepository {
    // Returns map of filename -> content
    suspend fun fetchAllContents(): Map<String, String>

    // Returns a serializable tree rooted at Documents
    suspend fun fetchTree(): com.korgnote.common.fs.Folder
}
