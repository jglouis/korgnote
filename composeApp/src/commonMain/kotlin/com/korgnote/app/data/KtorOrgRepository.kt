package com.korgnote.app.data

import com.korgnote.common.org.OrgDocument
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

class KtorOrgRepository(
    private val client: HttpClient,
    private val baseUrlProvider: () -> String,
) : OrgRepository {
    private fun baseUrl() = baseUrlProvider()

    override suspend fun fetchAllContents(): Map<String, OrgDocument> {
        // Expecting a JSON object of filename -> parsed OrgDocument
        return client.get("${baseUrl()}/api/org-content").body()
    }

    override suspend fun fetchTree(): com.korgnote.common.fs.Folder {
        return client.get("${baseUrl()}/api/org-tree-paths").body()
    }

    companion object {
        fun default(): HttpClient = platformBuildHttpClient()
    }
}

// Platform-specific client builder (Android adds Logging, others are minimal)
expect fun platformBuildHttpClient(): HttpClient
