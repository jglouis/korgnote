package com.korgnote.app.data

interface OrgRepository {
    // Returns map of filename -> content
    suspend fun fetchAllContents(): Map<String, String>

    // Returns list of relative file paths representing the tree under the root (e.g., "Org/Agenda/inbox.org")
    suspend fun fetchTreePaths(): List<String>
}
