package com.korgnote.app.data

interface OrgRepository {
    // Returns map of filename -> parsed OrgDocument
    suspend fun fetchAllContents(): Map<String, com.korgnote.common.org.OrgDocument>

    // Returns a serializable tree rooted at Documents
    suspend fun fetchTree(): com.korgnote.common.fs.Folder
}
