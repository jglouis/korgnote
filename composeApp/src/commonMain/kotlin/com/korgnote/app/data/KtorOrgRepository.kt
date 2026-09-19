package com.korgnote.app.data

import com.korgnote.common.fs.Folder
import com.korgnote.common.org.OrgDocument
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody

class KtorOrgRepository(
    private val client: HttpClient,
    private val baseUrlProvider: () -> String,
) : OrgRepository {
    private fun baseUrl() = baseUrlProvider()

    override suspend fun fetchAllContents(): Map<String, OrgDocument> {
        // Expecting a JSON object of filename -> parsed OrgDocument
        return client.get("${baseUrl()}/api/org-content").body()
    }

    override suspend fun fetchTree(): Folder {
        return client.get("${baseUrl()}/api/org-tree-paths").body()
    }

    override suspend fun fetchRawContent(path: String): String {
        return client.get("${baseUrl()}/api/content/$path").body()
    }

    override suspend fun saveContent(path: String, content: String) {
        client.post("${baseUrl()}/api/content/$path") {
            setBody(content)
        }
    }
}
